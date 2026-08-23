package com.example.offlinedemo.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConfigurationProperties(prefix = "kunlun.platform")
public class PlatformProperties {
    private String dataDir = "../.kunlun-builder";
    private String projectRoot = "..";
    private String secretKey = "";
    private String adminToken = "";
    private int maxAnalysisFiles = 6000;
    private int commandTimeoutMinutes = 60;

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public String getProjectRoot() { return projectRoot; }
    public void setProjectRoot(String projectRoot) { this.projectRoot = projectRoot; }
    public Path dataDirPath() { return Path.of(dataDir); }
    public Path projectRootPath() { return Path.of(projectRoot); }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getAdminToken() { return adminToken; }
    public void setAdminToken(String adminToken) { this.adminToken = adminToken; }
    public int getMaxAnalysisFiles() { return maxAnalysisFiles; }
    public void setMaxAnalysisFiles(int maxAnalysisFiles) { this.maxAnalysisFiles = maxAnalysisFiles; }
    public int getCommandTimeoutMinutes() { return commandTimeoutMinutes; }
    public void setCommandTimeoutMinutes(int commandTimeoutMinutes) { this.commandTimeoutMinutes = commandTimeoutMinutes; }
}
