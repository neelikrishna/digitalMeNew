package com.digitalself.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "digitalself.intent")
public class IntentProperties {

    /**
     * Kill switch. When false, every question is treated as a memory question —
     * the behaviour before routing existed. Strictly safe: it can refuse to
     * answer, never invent.
     */
    private boolean enabled = true;

    /**
     * Model used to classify. Empty means reuse the chat model.
     *
     * <p>Worth setting to something small. Classification adds a model call to
     * every question, and it only has to emit one word — a 1B model does it in a
     * fraction of the time a larger answering model would.
     */
    private String model = "";

    /**
     * Below this, the decision is treated as a guess and routed to the strictest
     * branch rather than acted on.
     */
    private double minConfidence = 0.5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public double getMinConfidence() {
        return minConfidence;
    }

    public void setMinConfidence(double minConfidence) {
        this.minConfidence = minConfidence;
    }
}
