package com.digitalself.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "digitalself.extraction")
public class ExtractionProperties {

    /**
     * Candidates at or above this confidence become memories without waiting for
     * review; anything below queues as a proposal. Applies only to brand-new
     * memories — an extraction that would change an existing memory always waits,
     * because silently rewriting a memory is the one thing this system must not do.
     *
     * <p>Set above 1.0 to require review of everything.
     */
    private double autoAcceptConfidence = 0.9;

    public double getAutoAcceptConfidence() {
        return autoAcceptConfidence;
    }

    public void setAutoAcceptConfidence(double autoAcceptConfidence) {
        this.autoAcceptConfidence = autoAcceptConfidence;
    }
}
