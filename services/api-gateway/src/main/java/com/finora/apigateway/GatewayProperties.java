package com.finora.apigateway;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Gateway settings. Numeric limits are engineering starting points (DEC-50) and are all configurable. */
@ConfigurationProperties("finora.gateway")
public class GatewayProperties {

    /** Private base URL of each owning service, keyed by service name. */
    private Map<String, String> services = new LinkedHashMap<>();
    private Duration readTimeout = Duration.ofSeconds(5);
    private Duration writeTimeout = Duration.ofSeconds(10);
    private int maxRetries = 2;
    private long maxBodyBytes = 1_048_576;
    private long maxUploadBytes = 26_214_400;
    private RateLimit rateLimit = new RateLimit();
    /** Browser origins allowed to make state-changing requests (CSRF defence in depth). */
    private List<String> allowedOrigins = List.of("http://localhost:3000");

    public static class RateLimit {
        private boolean enabled = true;
        private int capacity = 60;
        private double refillPerSecond = 10;
        private int authCapacity = 10;
        private double authRefillPerSecond = 0.2;
        private int maxTrackedSubjects = 100_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public double getRefillPerSecond() {
            return refillPerSecond;
        }

        public void setRefillPerSecond(double refillPerSecond) {
            this.refillPerSecond = refillPerSecond;
        }

        public int getAuthCapacity() {
            return authCapacity;
        }

        public void setAuthCapacity(int authCapacity) {
            this.authCapacity = authCapacity;
        }

        public double getAuthRefillPerSecond() {
            return authRefillPerSecond;
        }

        public void setAuthRefillPerSecond(double authRefillPerSecond) {
            this.authRefillPerSecond = authRefillPerSecond;
        }

        public int getMaxTrackedSubjects() {
            return maxTrackedSubjects;
        }

        public void setMaxTrackedSubjects(int maxTrackedSubjects) {
            this.maxTrackedSubjects = maxTrackedSubjects;
        }
    }

    public Map<String, String> getServices() {
        return services;
    }

    public void setServices(Map<String, String> services) {
        this.services = services;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Duration getWriteTimeout() {
        return writeTimeout;
    }

    public void setWriteTimeout(Duration writeTimeout) {
        this.writeTimeout = writeTimeout;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    public long getMaxUploadBytes() {
        return maxUploadBytes;
    }

    public void setMaxUploadBytes(long maxUploadBytes) {
        this.maxUploadBytes = maxUploadBytes;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }
}
