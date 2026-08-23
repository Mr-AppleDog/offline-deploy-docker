package com.example.offlinedemo.platform.security;

import com.example.offlinedemo.platform.config.PlatformProperties;
import com.example.offlinedemo.platform.store.PlatformStore;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class CryptoService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String configuredKey;
    private final Path keyFile;
    private SecretKey key;

    public CryptoService(PlatformProperties properties, PlatformStore store) {
        this.configuredKey = properties.getSecretKey();
        this.keyFile = store.root().resolve("master.key");
    }

    @PostConstruct
    public void initialize() throws Exception {
        byte[] raw;
        if (configuredKey != null && !configuredKey.isBlank()) {
            raw = Base64.getDecoder().decode(configuredKey.trim());
        } else if (Files.isRegularFile(keyFile)) {
            raw = Base64.getDecoder().decode(Files.readString(keyFile).trim());
        } else {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            raw = generator.generateKey().getEncoded();
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(raw),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        if (raw.length != 32) throw new IllegalStateException("KUNLUN_SECRET_KEY 必须是 Base64 编码的 32 字节密钥");
        key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return null;
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] packed = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, packed, 0, nonce.length);
            System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            throw new IllegalStateException("加密敏感配置失败", e);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) return "";
        try {
            byte[] packed = Base64.getDecoder().decode(ciphertext);
            if (packed.length < 29) throw new IllegalArgumentException("密文格式错误");
            byte[] nonce = java.util.Arrays.copyOfRange(packed, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(packed, 12, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密敏感配置失败，请检查平台主密钥", e);
        }
    }

    public String generatePassword() {
        byte[] value = new byte[24];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
