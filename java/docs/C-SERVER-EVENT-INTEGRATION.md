# C Server Event Integration Guide

**Quick Start: Implement Event Publishing to Saving-Gateway**

```
Architecture:
  C Server → HTTP POST /api/events (Saving-Gateway)
             └─ Immediate ACK (< 10ms)
             └─ Fire-and-forget (no wait for Java response)
             └─ Retry locally if gateway unreachable
```

---

## 1. Snowflake ID Generator (C)

```c
#include <stdint.h>
#include <time.h>
#include <pthread.h>

// Configuration
#define EPOCH_2017 1513872000000LL
#define DATACENTER_ID 1
#define WORKER_ID 5

typedef struct {
    int64_t last_timestamp;
    uint16_t sequence;
    pthread_mutex_t mutex;
} SnowflakeGen;

int64_t snowflake_next_id(SnowflakeGen *gen) {
    pthread_mutex_lock(&gen->mutex);
    
    int64_t now = current_time_ms();
    
    if (now < gen->last_timestamp) {
        pthread_mutex_unlock(&gen->mutex);
        return -1;  // Error: clock moved backwards
    }
    
    if (now == gen->last_timestamp) {
        gen->sequence = (gen->sequence + 1) & 0xFFF;
        if (gen->sequence == 0) {
            // Spin until next millisecond
            while (now == gen->last_timestamp) {
                now = current_time_ms();
            }
        }
    } else {
        gen->sequence = 0;
    }
    
    gen->last_timestamp = now;
    
    int64_t id = ((now - EPOCH_2017) << 22) |
                 ((int64_t)DATACENTER_ID << 17) |
                 ((int64_t)WORKER_ID << 12) |
                 gen->sequence;
    
    pthread_mutex_unlock(&gen->mutex);
    return id;
}

int64_t current_time_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}
```

---

## 2. Event Structure (C)

```c
#include <time.h>

typedef enum {
    EVENT_TRANSACTION_POSTED = 1,
    EVENT_TRANSACTION_FAILED = 2,
    EVENT_PAYMENT_SETTLED = 3,
    EVENT_FRAUD_DETECTED = 4,
    EVENT_BALANCE_UPDATED = 5,
    EVENT_SESSION_CREATED = 6,
    EVENT_SESSION_EXPIRED = 7,
    EVENT_SETTLEMENT_ERROR = 8
} EventType;

typedef struct {
    int64_t event_id;
    EventType event_type;
    int64_t timestamp;
    char request_id[64];
    char user_id[64];
    uint64_t correlation_id;
    int retry_count;
    char *data_json;  // Payload (malloc'd)
} LedgerEvent;

LedgerEvent* event_create(EventType type, const char *user_id, const char *request_id) {
    LedgerEvent *event = malloc(sizeof(LedgerEvent));
    event->event_id = snowflake_next_id(&gen);
    event->event_type = type;
    event->timestamp = current_time_ms();
    event->retry_count = 0;
    strcpy(event->request_id, request_id);
    strcpy(event->user_id, user_id);
    event->data_json = NULL;
    return event;
}

void event_set_data(LedgerEvent *event, const char *json) {
    event->data_json = strdup(json);
}

void event_free(LedgerEvent *event) {
    if (event->data_json) free(event->data_json);
    free(event);
}
```

---

## 3. HTTP Publisher to Saving-Gateway (C)

```c
#include <curl/curl.h>
#include <cjson/cJSON.h>

typedef struct {
    char *gateway_url;         // "https://saving-gateway.internal/api/events"
    char *api_key;             // Bearer token for auth
    CURL *curl;
} GatewayClient;

// Initialize gateway client
GatewayClient* gateway_init(const char *url, const char *key) {
    GatewayClient *client = malloc(sizeof(GatewayClient));
    client->gateway_url = strdup(url);
    client->api_key = strdup(key);
    client->curl = curl_easy_init();
    
    if (!client->curl) {
        fprintf(stderr, "Failed to init CURL\n");
        free(client);
        return NULL;
    }
    
    // Set default options
    curl_easy_setopt(client->curl, CURLOPT_TIMEOUT, 5L);  // 5 second timeout
    curl_easy_setopt(client->curl, CURLOPT_NOSIGNAL, 1L);  // Thread-safe
    
    return client;
}

// Publish event to Saving-Gateway
int gateway_publish_event(GatewayClient *client, LedgerEvent *event) {
    // 1. Serialize event to JSON
    cJSON *json = cJSON_CreateObject();
    cJSON_AddNumberToObject(json, "event_id", event->event_id);
    cJSON_AddStringToObject(json, "event_type", event_type_str(event->event_type));
    cJSON_AddNumberToObject(json, "timestamp", event->timestamp);
    cJSON_AddStringToObject(json, "source", "wire-c-server");
    cJSON_AddStringToObject(json, "request_id", event->request_id);
    cJSON_AddStringToObject(json, "user_id", event->user_id);
    cJSON_AddNumberToObject(json, "correlation_id", event->correlation_id);
    
    // Add data payload
    cJSON *data = cJSON_Parse(event->data_json);
    cJSON_AddItemToObject(json, "data", data);
    
    char *json_str = cJSON_Print(json);
    
    // 2. Build headers with auth
    struct curl_slist *headers = NULL;
    headers = curl_slist_append(headers, "Content-Type: application/json");
    
    char auth_header[512];
    snprintf(auth_header, sizeof(auth_header), "Authorization: Bearer %s", 
             client->api_key);
    headers = curl_slist_append(headers, auth_header);
    
    // 3. POST to gateway
    curl_easy_setopt(client->curl, CURLOPT_URL, client->gateway_url);
    curl_easy_setopt(client->curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(client->curl, CURLOPT_POSTFIELDS, json_str);
    
    struct MemoryStruct response = {0};
    curl_easy_setopt(client->curl, CURLOPT_WRITEFUNCTION, write_callback);
    curl_easy_setopt(client->curl, CURLOPT_WRITEDATA, &response);
    
    CURLcode res = curl_easy_perform(client->curl);
    
    // 4. Check response
    int success = 0;
    if (res == CURLE_OK) {
        long http_code = 0;
        curl_easy_getinfo(client->curl, CURLINFO_RESPONSE_CODE, &http_code);
        
        if (http_code == 200) {
            // Gateway accepted event
            log_debug("Event accepted by gateway: event_id=%ld", 
                      event->event_id);
            success = 1;
        } else if (http_code == 429) {
            // Rate limited
            log_warn("Gateway rate limit: event_id=%ld", event->event_id);
            success = 0;  // Will retry
        } else {
            log_error("Gateway error: http_code=%ld", http_code);
            success = 0;
        }
    } else {
        log_error("CURL error: %s", curl_easy_strerror(res));
        success = 0;
    }
    
    // Cleanup
    cJSON_Delete(json);
    free(json_str);
    curl_slist_free_all(headers);
    free(response.memory);
    
    return success ? 0 : -1;
}

void gateway_close(GatewayClient *client) {
    if (client) {
        curl_easy_cleanup(client->curl);
        free(client->gateway_url);
        free(client->api_key);
        free(client);
    }
}

// Helper: response handler
struct MemoryStruct {
    char *memory;
    size_t size;
};

static size_t write_callback(void *contents, size_t size, size_t nmemb, void *userp) {
    size_t realsize = size * nmemb;
    struct MemoryStruct *mem = (struct MemoryStruct *)userp;
    char *ptr = realloc(mem->memory, mem->size + realsize + 1);
    if (!ptr) return 0;
    
    mem->memory = ptr;
    memcpy(&(mem->memory[mem->size]), contents, realsize);
    mem->size += realsize;
    mem->memory[mem->size] = 0;
    
    return realsize;
}
```

---

## 4. Event Publishing to Saving-Gateway (Integration)

```c
// Hook: After transaction posted to Java ledger
void on_transaction_posted(const char *user_id, int64_t trans_id, 
                          const char *ref_id, int64_t amount) {
    // Create event
    LedgerEvent *event = event_create(EVENT_TRANSACTION_POSTED, 
                                      user_id, ref_id);
    
    // Build payload JSON
    char json_data[512];
    snprintf(json_data, sizeof(json_data),
        "{\"trans_id\":%ld,\"ref_id\":\"%s\",\"amount\":%ld,"
        "\"status\":\"POSTED\",\"posted_at\":%ld}",
        trans_id, ref_id, amount, current_time_ms());
    
    event_set_data(event, json_data);
    
    // Publish to Saving-Gateway (fire-and-forget)
    if (gateway_publish_event(&gateway_client, event) != 0) {
        // Failed to reach gateway: queue for retry
        queue_event_for_retry(event, 1000);  // Retry in 1 second
    }
    
    event_free(event);
}

// Hook: On payment settlement
void on_payment_settled(const char *user_id, int64_t trans_id,
                       int64_t amount, const char *merchant_id) {
    LedgerEvent *event = event_create(EVENT_PAYMENT_SETTLED, 
                                      user_id, "");
    
    char json_data[512];
    snprintf(json_data, sizeof(json_data),
        "{\"trans_id\":%ld,\"amount\":%ld,\"merchant_id\":\"%s\","
        "\"settlement_time\":%ld}",
        trans_id, amount, merchant_id, current_time_ms());
    
    event_set_data(event, json_data);
    
    // Publish to gateway
    if (gateway_publish_event(&gateway_client, event) != 0) {
        queue_event_for_retry(event, 1000);
    }
    
    event_free(event);
}

// Hook: On fraud detection
void on_fraud_detected(const char *user_id, int num_txns, int time_window) {
    LedgerEvent *event = event_create(EVENT_FRAUD_DETECTED, user_id, "");
    
    char json_data[512];
    snprintf(json_data, sizeof(json_data),
        "{\"alert_type\":\"HIGH_FREQUENCY\",\"transactions\":%d,"
        "\"time_window\":%d,\"action\":\"BLOCK_USER\"}",
        num_txns, time_window);
    
    event_set_data(event, json_data);
    
    // Publish to gateway
    if (gateway_publish_event(&gateway_client, event) != 0) {
        queue_event_for_retry(event, 1000);
    }
    
    event_free(event);
}
```

---

## 5. Retry Queue (Local Buffer)

If gateway is unreachable, C server buffers events locally and retries with exponential backoff.

---

## 6. Local Retry Queue (Gateway Unreachable)

```c
typedef struct {
    LedgerEvent *event;
    int64_t retry_at;
    int retry_count;
} QueuedEvent;

// Retry queue (linked list)
typedef struct {
    QueuedEvent *events;
    int count;
    int capacity;
    pthread_mutex_t mutex;
} RetryQueue;

void queue_event_for_retry(LedgerEvent *event, int delay_ms) {
    pthread_mutex_lock(&retry_queue.mutex);
    
    if (retry_queue.count >= retry_queue.capacity) {
        retry_queue.capacity *= 2;
        retry_queue.events = realloc(retry_queue.events, 
                                     retry_queue.capacity * sizeof(QueuedEvent));
    }
    
    QueuedEvent queued;
    queued.event = event;  // Copy data
    queued.retry_at = current_time_ms() + delay_ms;
    queued.retry_count = event->retry_count + 1;
    
    retry_queue.events[retry_queue.count++] = queued;
    
    pthread_mutex_unlock(&retry_queue.mutex);
}

// Background thread: process retry queue
void* retry_thread(void *arg) {
    while (1) {
        sleep(1);  // Check every second
        
        pthread_mutex_lock(&retry_queue.mutex);
        
        for (int i = 0; i < retry_queue.count; i++) {
            QueuedEvent *queued = &retry_queue.events[i];
            
            if (current_time_ms() >= queued->retry_at) {
                int res = rabbitmq_publish_event(&rabbitmq_pool, queued->event);
                
                if (res == 0) {
                    // Success: remove from queue
                    memmove(&retry_queue.events[i], 
                            &retry_queue.events[i + 1],
                            (retry_queue.count - i - 1) * sizeof(QueuedEvent));
                    retry_queue.count--;
                } else if (queued->retry_count < 6) {
                    // Retry with exponential backoff
                    int delay = (1 << (queued->retry_count - 1)) * 5000;  // 5s, 10s, 20s, ...
                    queued->retry_at = current_time_ms() + delay;
                    queued->event->retry_count++;
                } else {
                    // Max retries: log and remove
                    fprintf(stderr, "Event %ld exceeded max retries\n", 
                            queued->event->event_id);
                    memmove(&retry_queue.events[i], 
                            &retry_queue.events[i + 1],
                            (retry_queue.count - i - 1) * sizeof(QueuedEvent));
                    retry_queue.count--;
                }
            }
        }
        
        pthread_mutex_unlock(&retry_queue.mutex);
    }
    return NULL;
}
```

---

## 7. Integration with Wire Protocol

```c
// In wire protocol handler (after posting transaction to Java)
void handle_transaction_wire_request(WireFrame *frame) {
    TxnRequest *txn = parse_transaction_payload(frame);
    
    // 1. Call Java ledger via HTTP
    TxnResponse response;
    int res = call_java_ledger(txn, &response);
    
    if (res == 0) {
        // 2. Publish event asynchronously
        on_transaction_posted(txn->user_id, response.trans_id, 
                             txn->ref_id, txn->amount);
        
        // 3. Return wire response immediately (don't wait for event)
        send_transaction_response(frame->correlation_id, &response);
    } else {
        // 3. Publish failure event
        on_transaction_failed(txn->user_id, txn->ref_id, 
                             res, "JAVA_LEDGER_ERROR");
        
        send_error_response(frame->correlation_id, 0x0009);
    }
}
```

---

## 8. Startup Sequence

```c
int main(int argc, char *argv[]) {
    // 1. Initialize Snowflake ID generator
    memset(&gen, 0, sizeof(SnowflakeGen));
    pthread_mutex_init(&gen.mutex, NULL);
    log_info("Snowflake ID generator initialized (datacenter=1, worker=5)");
    
    // 2. Initialize Saving-Gateway HTTP client
    const char *gateway_url = getenv("GATEWAY_URL") ?: 
                              "https://saving-gateway.internal/api/events";
    const char *api_key = getenv("GATEWAY_API_KEY");
    
    if (!api_key) {
        fprintf(stderr, "GATEWAY_API_KEY environment variable not set\n");
        return 1;
    }
    
    gateway_client = gateway_init(gateway_url, api_key);
    if (!gateway_client) {
        fprintf(stderr, "Failed to initialize gateway client\n");
        return 1;
    }
    log_info("Gateway client initialized: url=%s", gateway_url);
    
    // 3. Initialize local retry queue
    retry_queue.capacity = 1000;
    retry_queue.events = malloc(1000 * sizeof(QueuedEvent));
    pthread_mutex_init(&retry_queue.mutex, NULL);
    log_info("Local retry queue initialized (capacity=%d)", retry_queue.capacity);
    
    // 4. Start retry thread (process retries every 1 second)
    pthread_t retry_tid;
    pthread_create(&retry_tid, NULL, retry_thread, NULL);
    log_info("Retry thread started");
    
    // 5. Start metrics reporter (every 10 seconds)
    pthread_t metrics_tid;
    pthread_create(&metrics_tid, NULL, metrics_thread, NULL);
    log_info("Metrics reporter started");
    
    // 6. Start wire protocol server
    if (start_wire_server(7474) != 0) {
        fprintf(stderr, "Failed to start wire server\n");
        return 1;
    }
    log_info("Wire protocol server started on :7474");
    
    return 0;
}

// Metrics reporting (for monitoring)
void* metrics_thread(void *arg) {
    while (1) {
        sleep(10);
        
        log_info("metrics",
            kv("events_sent", stats.total_sent),
            kv("events_failed", stats.total_failed),
            kv("gateway_timeout", stats.gateway_timeout_count),
            kv("retry_queue_size", retry_queue.count),
            kv("uptime_seconds", uptime())
        );
    }
    return NULL;
}
```

---

## Integration Checklist (C Server)

**Core:**
- [ ] Snowflake ID generator working
- [ ] HTTP client to Saving-Gateway (TLS + auth)
- [ ] Event creation (TRANSACTION_POSTED, PAYMENT_SETTLED, FRAUD_DETECTED, etc.)
- [ ] Event serialization to JSON (with data payload)

**Publishing:**
- [ ] Send to Saving-Gateway POST /api/events
- [ ] Fire-and-forget (immediate ACK, don't wait for Java)
- [ ] Validate HTTP 200 response
- [ ] Handle HTTP 429 (rate limit) → retry

**Reliability:**
- [ ] Local retry queue (exponential backoff: 1s, 2s, 4s, 8s, 16s, 32s)
- [ ] Circuit breaker pattern (if gateway unreachable for 10s)
- [ ] Max retry attempts: 6
- [ ] Metrics: total_sent, total_failed, retry_queue_size

**Integration Points:**
- [ ] on_transaction_posted() hook
- [ ] on_payment_settled() hook
- [ ] on_fraud_detected() hook
- [ ] on_session_created() hook
- [ ] on_user_balance_updated() hook

**Testing:**
- [ ] Unit: event serialization
- [ ] Unit: Snowflake ID generation (monotonic, unique)
- [ ] Integration: send event to gateway mock
- [ ] Load: 1000 events/sec sustained
- [ ] Chaos: gateway timeout → verify local retry queue

**Monitoring:**
- [ ] Metrics: events_sent, events_failed, retry_queue_size
- [ ] Logging: INFO on success, WARN on retry, ERROR on max retries
- [ ] Dashboard: retry queue depth trend

---

## Testing

```bash
# Test Snowflake ID generation
./test_snowflake_id  # Should output unique, monotonic IDs

# Test event publishing
./test_event_publish  # Should see events in RabbitMQ queue

# Integration test
./test_c_to_java  # C server → RabbitMQ → Java Ledger

# Load test
./load_test_events -r 1000 -d 60  # 1000 events/sec for 60 seconds
```

