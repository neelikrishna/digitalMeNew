package com.digitalself.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "digitalself.file-extraction")
public class FileExtractionProperties {

    /**
     * Hard ceiling on characters taken from one document. Document parsers are a
     * real attack surface — an OOXML file is a zip, and a small one can expand to
     * gigabytes. The limit is what stops that exhausting the heap; text past it
     * is dropped and the extraction marked truncated rather than failed.
     */
    private int writeLimitChars = 500_000;

    /**
     * Wall-clock ceiling for parsing one document, so a pathological file cannot
     * pin a thread indefinitely.
     */
    private int timeoutSeconds = 60;

    /**
     * Below this many characters, extracted text is treated as nothing worth
     * indexing. Guards against a PDF whose only "text" is a page number, which
     * would otherwise become a memory saying "1".
     */
    private int minCharsToIndex = 20;

    public int getWriteLimitChars() {
        return writeLimitChars;
    }

    public void setWriteLimitChars(int writeLimitChars) {
        this.writeLimitChars = writeLimitChars;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMinCharsToIndex() {
        return minCharsToIndex;
    }

    public void setMinCharsToIndex(int minCharsToIndex) {
        this.minCharsToIndex = minCharsToIndex;
    }
}
