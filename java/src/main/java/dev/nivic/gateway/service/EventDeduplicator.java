package dev.nivic.gateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
public class EventDeduplicator {

    @Autowired
    private RedisTemplate<String, String> redis;

    private static final String DEDUP_KEY_PREFIX = "dedup:";
    private static final long DEDUP_TTL_SECONDS = 300;  // 5 minutes

    /**
     * Check if event_id already processed
     */
    public boolean isDuplicate(long eventId) {
        String key = DEDUP_KEY_PREFIX + eventId;
        Boolean exists = redis.hasKey(key);
        return exists != null && exists;
    }

    /**
     * Record processed event in dedup cache
     */
    public void recordProcessed(long eventId, String eventType) {
        String key = DEDUP_KEY_PREFIX + eventId;
        try {
            redis.opsForValue().set(
                key,
                eventType,
                Duration.ofSeconds(DEDUP_TTL_SECONDS)
            );
        } catch (Exception e) {
            log.error("Error recording processed event: event_id={}", eventId, e);
            // Non-fatal: continue processing even if dedup cache fails
        }
    }

    /**
     * Get dedup cache size (for monitoring)
     */
    public long getCacheSize() {
        try {
            Long size = redis.keys(DEDUP_KEY_PREFIX + "*").stream().count();
            return size != null ? size : 0;
        } catch (Exception e) {
            log.warn("Error getting dedup cache size", e);
            return -1;
        }
    }

    /**
     * Clear old dedup entries (scheduled cleanup)
     */
    public void cleanup() {
        try {
            // Redis TTL handles cleanup automatically
            log.debug("Dedup cache TTL-based cleanup");
        } catch (Exception e) {
            log.error("Error during cleanup", e);
        }
    }
}
