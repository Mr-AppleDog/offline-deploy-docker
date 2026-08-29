package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.store.BlobStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 以恒定内存流式传输大文件，并支持单段 HTTP Range 断点续传。 */
@Service
public class DownloadService {
    private static final int COPY_BUFFER_SIZE = 128 * 1024;
    private final BlobStore blobStore;

    public DownloadService(BlobStore blobStore) {
        this.blobStore = blobStore;
    }

    public void downloadLocal(Path path, String fileName, String checksum,
                              HttpServletRequest request, HttpServletResponse response) throws Exception {
        long size = Files.size(path);
        send(fileName, checksum, size, (offset, length) -> openLocal(path, offset), request, response);
    }

    public void downloadBlob(BlobStore.BlobRef ref, String fileName, String checksum,
                             HttpServletRequest request, HttpServletResponse response) throws Exception {
        long size = blobStore.size(ref);
        send(fileName, checksum, size, (offset, length) -> blobStore.open(ref, offset, length), request, response);
    }

    private void send(String fileName, String checksum, long totalSize, StreamOpener opener,
                      HttpServletRequest request, HttpServletResponse response) throws Exception {
        String etag = checksum == null || checksum.isBlank() ? null : '"' + checksum.replace("\"", "") + '"';
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString());
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        if (etag != null) response.setHeader(HttpHeaders.ETAG, etag);

        String rangeHeader = request.getHeader(HttpHeaders.RANGE);
        String ifRange = request.getHeader(HttpHeaders.IF_RANGE);
        if (rangeHeader != null && ifRange != null && (etag == null || !ifRange.trim().equals(etag))) {
            rangeHeader = null;
        }

        ByteRange range;
        try {
            range = parseRange(rangeHeader, totalSize);
        } catch (IllegalArgumentException exception) {
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + totalSize);
            response.setContentLengthLong(0);
            return;
        }

        if (range.partial()) {
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader(HttpHeaders.CONTENT_RANGE,
                    "bytes " + range.start() + "-" + range.end() + "/" + totalSize);
        } else {
            response.setStatus(HttpServletResponse.SC_OK);
        }
        response.setContentLengthLong(range.length());

        if ("HEAD".equalsIgnoreCase(request.getMethod()) || range.length() == 0) return;

        response.setBufferSize(COPY_BUFFER_SIZE);
        try (InputStream input = opener.open(range.start(), range.length())) {
            copyExactly(input, response.getOutputStream(), range.length());
        }
    }

    static ByteRange parseRange(String header, long totalSize) {
        if (totalSize < 0) throw new IllegalArgumentException("文件长度无效");
        if (header == null || header.isBlank()) {
            return new ByteRange(0, totalSize == 0 ? -1 : totalSize - 1, totalSize, false);
        }
        if (!header.startsWith("bytes=") || header.indexOf(',') >= 0 || totalSize == 0) {
            throw new IllegalArgumentException("Range 格式无效");
        }
        String value = header.substring("bytes=".length()).trim();
        int separator = value.indexOf('-');
        if (separator < 0 || value.indexOf('-', separator + 1) >= 0) {
            throw new IllegalArgumentException("Range 格式无效");
        }
        try {
            if (separator == 0) {
                long suffixLength = Long.parseLong(value.substring(1));
                if (suffixLength <= 0) throw new IllegalArgumentException("Range 格式无效");
                long length = Math.min(suffixLength, totalSize);
                return new ByteRange(totalSize - length, totalSize - 1, length, true);
            }
            long start = Long.parseLong(value.substring(0, separator));
            if (start < 0 || start >= totalSize) throw new IllegalArgumentException("Range 超出文件长度");
            String endText = value.substring(separator + 1);
            long end = endText.isBlank() ? totalSize - 1 : Long.parseLong(endText);
            if (end < start) throw new IllegalArgumentException("Range 格式无效");
            end = Math.min(end, totalSize - 1);
            return new ByteRange(start, end, end - start + 1, true);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Range 格式无效", exception);
        }
    }

    private static InputStream openLocal(Path path, long offset) throws IOException {
        SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ);
        try {
            channel.position(offset);
            return Channels.newInputStream(channel);
        } catch (Exception exception) {
            channel.close();
            throw exception;
        }
    }

    private static void copyExactly(InputStream input, OutputStream output, long length) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long remaining = length;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("下载源在预期长度之前结束");
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    @FunctionalInterface
    private interface StreamOpener {
        InputStream open(long offset, long length) throws Exception;
    }

    record ByteRange(long start, long end, long length, boolean partial) {}
}
