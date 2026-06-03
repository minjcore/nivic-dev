package dev.nivic.gateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class LedgerEvent implements Serializable {

    @JsonProperty("event_id")
    private long eventId;

    @JsonProperty("event_type")
    private String eventType;

    @JsonProperty("timestamp")
    private long timestamp;

    @JsonProperty("source")
    private String source;

    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("correlation_id")
    private long correlationId;

    @JsonProperty("data")
    private Map<String, Object> data = new HashMap<>();

    @JsonProperty("retry_count")
    private int retryCount = 0;

    // Additional fields (set by gateway)
    private String sourceCServer;
    private long gatewayReceivedAt;
    private String routingKey;

    public LedgerEvent() {}

    public LedgerEvent(long eventId, String eventType, String userId) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.userId = userId;
        this.timestamp = System.currentTimeMillis();
    }

    // Getters & Setters
    public long getEventId() {
        return eventId;
    }

    public void setEventId(long eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public long getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(long correlationId) {
        this.correlationId = correlationId;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public String getSourceCServer() {
        return sourceCServer;
    }

    public void setSourceCServer(String sourceCServer) {
        this.sourceCServer = sourceCServer;
    }

    public long getGatewayReceivedAt() {
        return gatewayReceivedAt;
    }

    public void setGatewayReceivedAt(long gatewayReceivedAt) {
        this.gatewayReceivedAt = gatewayReceivedAt;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public void setRoutingKey(String routingKey) {
        this.routingKey = routingKey;
    }

    @Override
    public String toString() {
        return "LedgerEvent{" +
                "eventId=" + eventId +
                ", eventType='" + eventType + '\'' +
                ", userId='" + userId + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
