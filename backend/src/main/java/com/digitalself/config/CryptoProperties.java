package com.digitalself.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "digitalself.crypto")
public class CryptoProperties {

    /** Base64-encoded 32-byte key. Must come from the environment, never from source. */
    private String masterKey = "";

    /** Recorded against each wrapped key so a future rotation can tell them apart. */
    private String masterKeyLabel = "default";

    /** Directory holding encrypted file blobs. */
    private String storagePath = "./data/files";

    public String getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }

    public String getMasterKeyLabel() {
        return masterKeyLabel;
    }

    public void setMasterKeyLabel(String masterKeyLabel) {
        this.masterKeyLabel = masterKeyLabel;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }
}
