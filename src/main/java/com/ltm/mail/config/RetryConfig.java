package com.ltm.mail.config;

import com.google.gson.annotations.SerializedName;

/**
 * Cấu hình cơ chế thử lại (Retry) khi gặp lỗi tạm thời
 */
public class RetryConfig {

    @SerializedName("MaxRetry")
    private int maxRetry = 3;

    @SerializedName("BaseDelaySeconds")
    private int baseDelaySeconds = 2;

    @SerializedName("MaxDelaySeconds")
    private int maxDelaySeconds = 60;

    public RetryConfig() {
    }

    public RetryConfig(int maxRetry, int baseDelaySeconds, int maxDelaySeconds) {
        this.maxRetry = maxRetry;
        this.baseDelaySeconds = baseDelaySeconds;
        this.maxDelaySeconds = maxDelaySeconds;
    }

    public int getMaxRetry() {
        return maxRetry;
    }

    public void setMaxRetry(int maxRetry) {
        this.maxRetry = maxRetry;
    }

    public int getBaseDelaySeconds() {
        return baseDelaySeconds;
    }

    public void setBaseDelaySeconds(int baseDelaySeconds) {
        this.baseDelaySeconds = baseDelaySeconds;
    }

    public int getMaxDelaySeconds() {
        return maxDelaySeconds;
    }

    public void setMaxDelaySeconds(int maxDelaySeconds) {
        this.maxDelaySeconds = maxDelaySeconds;
    }

    @Override
    public String toString() {
        return "RetryConfig{" +
                "maxRetry=" + maxRetry +
                ", baseDelaySeconds=" + baseDelaySeconds +
                ", maxDelaySeconds=" + maxDelaySeconds +
                '}';
    }
}
