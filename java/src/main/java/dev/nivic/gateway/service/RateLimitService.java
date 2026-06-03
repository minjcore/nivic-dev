package dev.nivic.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.RateLimiter;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Service
public class RateLimitService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RateLimitService.class);


    private final LoadingCache<String, RateLimiter> rateLimiters = CacheBuilder.newBuilder()
        .expireAfterAccess(1, TimeUnit.HOURS)
        .build(new CacheLoader<String, RateLimiter>() {
            @Override
            public RateLimiter load(String clientId) {
                // Create rate limiter for this client (per-client limit)
                return RateLimiter.create(10000.0);  // 10k events/sec per client
            }
        });

    /**
     * Check if request is allowed (rate limit check)
     * Returns true if within limit, false if rate-limited
     */
    public boolean allowRequest(String clientId, double maxEventsPerSecond) {
        try {
            RateLimiter limiter = rateLimiters.get(clientId);
            // Try to acquire 1 permit (non-blocking)
            // If rate is exceeded, returns false
            return limiter.tryAcquire(1, 0, TimeUnit.SECONDS);

        } catch (ExecutionException e) {
            log.error("Error checking rate limit for client: {}", clientId, e);
            // Fail open: allow request if rate limiter fails
            return true;
        }
    }

    /**
     * Check rate limit with custom max rate
     */
    public boolean allowRequest(String clientId) {
        return allowRequest(clientId, 10000.0);  // Default: 10k/sec
    }

    /**
     * Reset rate limiter for a client (e.g., after timeout)
     */
    public void resetClient(String clientId) {
        rateLimiters.invalidate(clientId);
        log.info("Rate limiter reset for client: {}", clientId);
    }
}
