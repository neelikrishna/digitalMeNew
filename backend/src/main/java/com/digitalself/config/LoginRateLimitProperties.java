package com.digitalself.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties(prefix = "digitalself.auth")
public class LoginRateLimitProperties {

    @NestedConfigurationProperty
    private LoginRateLimit loginRateLimit = new LoginRateLimit();

    public LoginRateLimit getLoginRateLimit() {
        return loginRateLimit;
    }

    public void setLoginRateLimit(LoginRateLimit loginRateLimit) {
        this.loginRateLimit = loginRateLimit;
    }

    public static class LoginRateLimit {
        private int maxAttempts = 5;
        private int windowMinutes = 15;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getWindowMinutes() {
            return windowMinutes;
        }

        public void setWindowMinutes(int windowMinutes) {
            this.windowMinutes = windowMinutes;
        }
    }
}
