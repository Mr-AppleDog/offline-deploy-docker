package com.example.offlinedemo.platform.store;

import com.example.offlinedemo.platform.domain.Models;

/** 平台元数据的持久化后端。实现可交换：本地 JSON（回退）或真实 MySQL。 */
public interface MetadataStore {
    Models.PlatformState load() throws Exception;
    void save(Models.PlatformState state) throws Exception;
    /** 是否远端持久化（MySQL），用于对外态暴露。 */
    boolean remote();
}