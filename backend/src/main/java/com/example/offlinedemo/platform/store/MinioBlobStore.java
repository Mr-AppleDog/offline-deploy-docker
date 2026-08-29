package com.example.offlinedemo.platform.store;

import io.minio.BucketExistsArgs;
import io.minio.DownloadObjectArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.UploadObjectArgs;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** MinIO 对象存储后端。ref 即 object key。 */
public class MinioBlobStore implements BlobStore {
    private final MinioClient client;
    private final String bucket;

    public MinioBlobStore(String endpoint, String accessKey, String secretKey, String bucket) {
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
        ensureBucket();
    }

    private void ensureBucket() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        } catch (Exception e) {
            throw new IllegalStateException("初始化 MinIO Bucket 失败：" + bucket, e);
        }
    }

    @Override
    public BlobRef put(Path sourceFile, String objectName) throws Exception {
        client.uploadObject(UploadObjectArgs.builder()
                .bucket(bucket).object(objectName).filename(sourceFile.toAbsolutePath().toString()).build());
        return new BlobRef("minio", objectName);
    }

    @Override
    public Path materialize(BlobRef ref, String fileName, Path cacheDir) throws Exception {
        Files.createDirectories(cacheDir);
        Path destination = cacheDir.resolve(fileName == null || fileName.isBlank() ? "artifact" : fileName);
        if (Files.isRegularFile(destination) && Files.size(destination) > 0) return destination;
        client.downloadObject(DownloadObjectArgs.builder()
                .bucket(bucket).object(ref.ref()).filename(destination.toAbsolutePath().toString()).build());
        return destination;
    }

    @Override
    public InputStream open(BlobRef ref) throws Exception {
        return client.getObject(GetObjectArgs.builder().bucket(bucket).object(ref.ref()).build());
    }

    @Override
    public long size(BlobRef ref) throws Exception {
        return client.statObject(StatObjectArgs.builder().bucket(bucket).object(ref.ref()).build()).size();
    }

    @Override
    public InputStream open(BlobRef ref, long offset, long length) throws Exception {
        return client.getObject(GetObjectArgs.builder()
                .bucket(bucket).object(ref.ref()).offset(offset).length(length).build());
    }

    @Override
    public boolean remote() { return true; }
}
