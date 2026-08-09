package dev.stacklight.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under the {@code stacklight} prefix.
 *
 * <p>With no endpoint and key the client is created anyway and does nothing, so an
 * application can run in an environment without a collector without that being a case it
 * has to handle.
 */
@ConfigurationProperties(prefix = "stacklight")
public class StacklightProperties {

    private String endpoint = "";
    private String apiKey = "";
    private String service = "";
    private String release = "";

    private int queueCapacity = 512;
    private int batchSize = 20;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(5);
    private Duration retryBaseDelay = Duration.ofSeconds(1);
    private Duration retryMaxDelay = Duration.ofSeconds(30);
    private Duration shutdownTimeout = Duration.ofSeconds(3);
    private boolean debug = false;

    /** Captures exceptions that reach a controller, without altering how they are handled. */
    private boolean captureWebExceptions = true;

    /** Captures exceptions that kill a thread, delegating to whatever handler was already there. */
    private boolean captureUncaughtExceptions = true;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getRelease() {
        return release;
    }

    public void setRelease(String release) {
        this.release = release;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getRetryBaseDelay() {
        return retryBaseDelay;
    }

    public void setRetryBaseDelay(Duration retryBaseDelay) {
        this.retryBaseDelay = retryBaseDelay;
    }

    public Duration getRetryMaxDelay() {
        return retryMaxDelay;
    }

    public void setRetryMaxDelay(Duration retryMaxDelay) {
        this.retryMaxDelay = retryMaxDelay;
    }

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    public void setShutdownTimeout(Duration shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
    }

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    public boolean isCaptureWebExceptions() {
        return captureWebExceptions;
    }

    public void setCaptureWebExceptions(boolean captureWebExceptions) {
        this.captureWebExceptions = captureWebExceptions;
    }

    public boolean isCaptureUncaughtExceptions() {
        return captureUncaughtExceptions;
    }

    public void setCaptureUncaughtExceptions(boolean captureUncaughtExceptions) {
        this.captureUncaughtExceptions = captureUncaughtExceptions;
    }
}
