package com.example.offlinedemo.platform.store;

import java.io.InputStream;
import java.nio.file.Path;

/** 制品与交付物的对象存储后端：本地文件（回退）或 MinIO。引用语义：storeType + ref。 */
public interface BlobStore {
    /** 归档一个文件。local 时 target 为绝对目标路径；minio 时 target 为相对 object key。 */
    BlobRef put(Path sourceFile, String target) throws Exception;

    /** 就地或下载到 cacheDir，返回本地可读路径（供 docker load / 复制）。 */
    Path materialize(BlobRef ref, String fileName, Path cacheDir) throws Exception;

    /** 打开下载流。 */
    InputStream open(BlobRef ref) throws Exception;

    /** 返回制品字节数，供大文件下载设置 Content-Length 和校验 Range。 */
    long size(BlobRef ref) throws Exception;

    /**
     * 从指定偏移打开一段下载流。调用方只会读取 length 字节。
     * 远端对象存储应覆盖此方法，避免为了断点续传而从对象开头丢弃数据。
     */
    default InputStream open(BlobRef ref, long offset, long length) throws Exception {
        InputStream input = open(ref);
        try {
            input.skipNBytes(offset);
            return input;
        } catch (Exception exception) {
            input.close();
            throw exception;
        }
    }

    boolean remote();

    record BlobRef(String storeType, String ref) {
        public static BlobRef local(Path path) { return new BlobRef("local", path.toString()); }
    }
}
