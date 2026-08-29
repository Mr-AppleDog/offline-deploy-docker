package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.store.BlobStore;
import com.example.offlinedemo.platform.store.LocalBlobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DownloadServiceTest {
    @TempDir
    Path tempDir;

    private final DownloadService downloads = new DownloadService(new LocalBlobStore());

    @Test
    void streamsWholeFileWithLengthAndResumeHeaders() throws Exception {
        Path file = file();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/download");
        MockHttpServletResponse response = new MockHttpServletResponse();

        downloads.downloadLocal(file, "离线包.tar.gz", "abc123", request, response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentLengthLong()).isEqualTo(10);
        assertThat(response.getHeader(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(response.getHeader(HttpHeaders.ETAG)).isEqualTo("\"abc123\"");
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment");
        assertThat(response.getContentAsString()).isEqualTo("0123456789");
    }

    @Test
    void streamsRequestedByteRange() throws Exception {
        Path file = file();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/download");
        request.addHeader(HttpHeaders.RANGE, "bytes=2-5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        downloads.downloadLocal(file, "package.tar.gz", null, request, response);

        assertThat(response.getStatus()).isEqualTo(206);
        assertThat(response.getContentLengthLong()).isEqualTo(4);
        assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 2-5/10");
        assertThat(response.getContentAsString()).isEqualTo("2345");
    }

    @Test
    void supportsSuffixRange() throws Exception {
        Path file = file();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/download");
        request.addHeader(HttpHeaders.RANGE, "bytes=-3");
        MockHttpServletResponse response = new MockHttpServletResponse();

        downloads.downloadLocal(file, "package.tar.gz", null, request, response);

        assertThat(response.getStatus()).isEqualTo(206);
        assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 7-9/10");
        assertThat(response.getContentAsString()).isEqualTo("789");
    }

    @Test
    void rejectsUnsatisfiableRange() throws Exception {
        Path file = file();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/download");
        request.addHeader(HttpHeaders.RANGE, "bytes=99-");
        MockHttpServletResponse response = new MockHttpServletResponse();

        downloads.downloadLocal(file, "package.tar.gz", null, request, response);

        assertThat(response.getStatus()).isEqualTo(416);
        assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */10");
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void headReturnsHeadersWithoutBody() throws Exception {
        Path file = file();
        MockHttpServletRequest request = new MockHttpServletRequest("HEAD", "/download");
        MockHttpServletResponse response = new MockHttpServletResponse();

        downloads.downloadLocal(file, "package.tar.gz", null, request, response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentLengthLong()).isEqualTo(10);
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void forwardsRangeToObjectStorageInsteadOfDiscardingFromTheBeginning() throws Exception {
        BlobStore store = mock(BlobStore.class);
        BlobStore.BlobRef ref = new BlobStore.BlobRef("minio", "deliveries/package.tar.gz");
        when(store.size(ref)).thenReturn(10L);
        when(store.open(ref, 2, 4)).thenReturn(new ByteArrayInputStream("2345".getBytes(StandardCharsets.US_ASCII)));
        DownloadService objectDownloads = new DownloadService(store);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/download");
        request.addHeader(HttpHeaders.RANGE, "bytes=2-5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        objectDownloads.downloadBlob(ref, "package.tar.gz", null, request, response);

        assertThat(response.getContentAsString()).isEqualTo("2345");
        verify(store).open(ref, 2, 4);
    }

    @Test
    void reportsContentLengthBeyondTwoGigabytes() throws Exception {
        BlobStore store = mock(BlobStore.class);
        BlobStore.BlobRef ref = new BlobStore.BlobRef("minio", "deliveries/large.tar.gz");
        long fiveGiB = 5L * 1024 * 1024 * 1024;
        when(store.size(ref)).thenReturn(fiveGiB);
        DownloadService objectDownloads = new DownloadService(store);
        MockHttpServletRequest request = new MockHttpServletRequest("HEAD", "/download");
        MockHttpServletResponse response = new MockHttpServletResponse();

        objectDownloads.downloadBlob(ref, "large.tar.gz", null, request, response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentLengthLong()).isEqualTo(fiveGiB);
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    private Path file() throws Exception {
        Path file = tempDir.resolve("package.tar.gz");
        Files.writeString(file, "0123456789", StandardCharsets.US_ASCII);
        return file;
    }
}
