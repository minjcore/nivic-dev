# C Server Event Integration Guide

**Quick Start: Implement Event Publishing in C Server**

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

## 3. RabbitMQ Publisher (C)

```c
#include <amqp.h>
#include <amqp_tcp_socket.h>

typedef struct {
    amqp_connection_state_t conn;
    char *host;
    int port;
    char *username;
    char *password;
    char *vhost;
} RabbitMQPool;

// Initialize RabbitMQ connection
RabbitMQPool* rabbitmq_init(const char *host, int port, 
                            const char *user, const char *pass,
                            const char *vhost) {
    RabbitMQPool *pool = malloc(sizeof(RabbitMQPool));
    
    pool->conn = amqp_new_connection();
    amqp_socket_t *socket = amqp_tcp_socket_new(pool->conn);
    
    int status = amqp_socket_open(socket, host, port);
    if (status < 0) {
        fprintf(stderr, "Failed to open RabbitMQ socket\n");
        return NULL;
    }
    
    amqp_login(pool->conn, vhost, 0, 131072, 60, AMQP_SASL_METHOD_PLAIN,
               user, pass);
    
    amqp_channel_open(pool->conn, 1);
    
    return pool;
}

// Publish event to RabbitMQ
int rabbitmq_publish_event(RabbitMQPool *pool, LedgerEvent *event) {
    char routing_key[128];
    switch (event->event_type) {
        case EVENT_TRANSACTION_POSTED:
            strcpy(routing_key, "ledger.transaction_posted");
            break;
        case EVENT_PAYMENT_SETTLED:
            strcpy(routing_key, "ledger.payment_settled");
            break;
        case EVENT_FRAUD_DETECTED:
            strcpy(routing_key, "ledger.fraud_detected");
            break;
        // ... etc
    }
    
    // Serialize event to JSON
    char json_buffer[4096];
    event_to_json(event, json_buffer, sizeof(json_buffer));
    
    // Publish with mandatory flag
    amqp_basic_properties_t properties;
    properties.flags = AMQP_BASIC_CONTENT_TYPE_FLAG | 
                       AMQP_BASIC_PERSISTENT_FLAG;
    properties.content_type = amqp_cstring_bytes("application/json");
    properties.delivery_mode = 2;  // Persistent
    
    int res = amqp_basic_publish(
        pool->conn,
        1,                          // channel
        amqp_cstring_bytes("gtel-events"),
        amqp_cstring_bytes(routing_key),
        1,  // mandatory
        0,  // immediate
        &properties,
        amqp_cstring_bytes(json_buffer)
    );
    
    if (res < 0) {
        fprintf(stderr, "Failed to publish event\n");
        return -1;
    }
    
    // Wait for confirm
    amqp_rpc_reply_t reply = amqp_get_rpc_reply(pool->conn);
    if (reply.reply_type != AMQP_RESPONSE_NORMAL) {
        fprintf(stderr, "Publish failed: %d\n", reply.reply_type);
        return -1;
    }
    
    return 0;
}

void rabbitmq_close(RabbitMQPool *pool) {
    amqp_connection_close(pool->conn, AMQP_REPLY_SUCCESS);
    amqp_destroy_connection(pool->conn);
    free(pool);
}
```

---

## 4. Event Publishing (Integration)

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
    
    // Publish to RabbitMQ
    if (rabbitmq_publish_event(&rabbitmq_pool, event) != 0) {
        // Fallback: queue for retry
        queue_event_for_retry(event, 5000);
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
    rabbitmq_publish_event(&rabbitmq_pool, event);
    
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
    rabbitmq_publish_event(&rabbitmq_pool, event);
    
    event_free(event);
}
```

---

## 5. Fallback: HTTP Webhook Publisher (C)

```c
#include <curl/curl.h>
#include <openssl/hmac.h>

// Compute HMAC-SHA256
char* hmac_sha256(const char *data, const char *secret) {
    unsigned char hash[EVP_MAX_MD_SIZE];
    unsigned int hash_len = 0;
    
    HMAC(EVP_sha256(),
         (unsigned char*)secret, strlen(secret),
         (unsigned char*)data, strlen(data),
         hash, &hash_len);
    
    char *hex = malloc(hash_len * 2 + 1);
    for (unsigned int i = 0; i < hash_len; i++) {
        sprintf(hex + i * 2, "%02x", hash[i]);
    }
    hex[hash_len * 2] = '\0';
    return hex;
}

// POST event to Java webhook
int webhook_publish_event(LedgerEvent *event) {
    CURL *curl = curl_easy_init();
    if (!curl) return -1;
    
    char json_body[4096];
    event_to_json(event, json_body, sizeof(json_body));
    
    // Generate signature
    char *signature = hmac_sha256(json_body, webhook_secret);
    
    // Build headers
    struct curl_slist *headers = NULL;
    headers = curl_slist_append(headers, "Content-Type: application/json");
    
    char auth_header[256];
    snprintf(auth_header, sizeof(auth_header), 
             "X-Event-Signature: %s", signature);
    headers = curl_slist_append(headers, auth_header);
    
    char timestamp_header[64];
    snprintf(timestamp_header, sizeof(timestamp_header),
             "X-Timestamp: %ld", current_time_ms());
    headers = curl_slist_append(headers, timestamp_header);
    
    curl_easy_setopt(curl, CURLOPT_URL, "http://java-ledger:8090/api/events/push");
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, json_body);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 30L);
    
    CURLcode res = curl_easy_perform(curl);
    
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);
    free(signature);
    
    return (res == CURLE_OK) ? 0 : -1;
}
```

---

## 6. Retry Queue (C)

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
    
    // 2. Initialize RabbitMQ connection
    rabbitmq_pool = rabbitmq_init("rabbitmq.internal", 5672,
                                  "gtel-c-server", "secret", "/gtel-prod");
    if (!rabbitmq_pool) {
        fprintf(stderr, "Failed to connect to RabbitMQ\n");
        return 1;
    }
    
    // 3. Initialize retry queue
    retry_queue.capacity = 100;
    retry_queue.events = malloc(100 * sizeof(QueuedEvent));
    pthread_mutex_init(&retry_queue.mutex, NULL);
    
    // 4. Start retry thread
    pthread_t retry_tid;
    pthread_create(&retry_tid, NULL, retry_thread, NULL);
    
    // 5. Start wire protocol server
    start_wire_server(7474);
    
    return 0;
}
```

---

## Integration Checklist

- [ ] Snowflake ID generator working
- [ ] RabbitMQ connection pool initialized
- [ ] Event creation (TRANSACTION_POSTED, PAYMENT_SETTLED, etc.)
- [ ] RabbitMQ publisher (with retry logic)
- [ ] Webhook fallback (HTTP POST with HMAC)
- [ ] Retry queue with exponential backoff
- [ ] Event hooks integrated into transaction flow
- [ ] Fraud detection events
- [ ] Session lifecycle events
- [ ] Load testing (1000 events/sec)
- [ ] Monitoring + alerting
- [ ] Dead-letter queue monitoring

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

