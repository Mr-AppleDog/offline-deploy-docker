package com.example.offlinedemo.platform.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** 本地文件后端。ref 即绝对路径。 */
public class LocalBlobStore implements BlobStore {
    @Override
    public BlobRef put(Path sourceFile, String target) throws IOException {
        Path destination = Path.of(target).toAbsolutePath().normalize();
        Files.createDirectories(destination.getParent());
        Files.copy(sourceFile, destination, StandardCopyOption.REPLACE_EXISTING);
        return BlobRef.local(destination);
    }

    @Override
    public Path materialize(BlobRef ref, String fileName, Path cacheDir) {
        return Path.of(ref.ref());
    }

    @Override
    public InputStream open(BlobRef ref) throws IOException {
        Path path = Path.of(ref.ref());
        if (!Files.isRegularFile(path)) throw new IOException("文件不存在：" + path);
        return Files.newInputStream(path);
    }

    @Override
    public long size(BlobRef ref) throws IOException {
        Path path = Path.of(ref.ref());
        if (!Files.isRegularFile(path)) throw new IOException("文件不存在：" + path);
        return Files.size(path);
    }

    @Override
    public InputStream open(BlobRef ref, long offset, long length) throws IOException {
        Path path = Path.of(ref.ref());
        if (!Files.isRegularFile(path)) throw new IOException("文件不存在：" + path);
        SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ);
        try {
            channel.position(offset);
            return Channels.newInputStream(channel);
        } catch (Exception exception) {
            channel.close();
            throw exception;
        }
    }

    @Override
    public boolean remote() { return false; }
}
