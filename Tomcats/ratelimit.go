package main

import (
	"net/http"
	"sync"
	"time"
)

type rateLimiter struct {
	mu      sync.Mutex
	hits    map[string][]time.Time
	window  time.Duration
	maxHits int
}

func newRateLimiter(window time.Duration, maxHits int) *rateLimiter {
	rl := &rateLimiter{hits: make(map[string][]time.Time), window: window, maxHits: maxHits}
	go rl.cleanup()
	return rl
}

func (rl *rateLimiter) allow(key string) bool {
	now := time.Now()
	cutoff := now.Add(-rl.window)
	rl.mu.Lock()
	defer rl.mu.Unlock()
	times := rl.hits[key]
	valid := times[:0]
	for _, t := range times {
		if t.After(cutoff) {
			valid = append(valid, t)
		}
	}
	if len(valid) >= rl.maxHits {
		rl.hits[key] = valid
		return false
	}
	rl.hits[key] = append(valid, now)
	return true
}

func (rl *rateLimiter) cleanup() {
	for range time.Tick(5 * time.Minute) {
		cutoff := time.Now().Add(-rl.window)
		rl.mu.Lock()
		for k, times := range rl.hits {
			valid := times[:0]
			for _, t := range times {
				if t.After(cutoff) {
					valid = append(valid, t)
				}
			}
			if len(valid) == 0 {
				delete(rl.hits, k)
			} else {
				rl.hits[k] = valid
			}
		}
		rl.mu.Unlock()
	}
}

func (rl *rateLimiter) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip := r.RemoteAddr
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			ip = xff
		}
		if !rl.allow(ip) {
			http.Error(w, "too many requests", http.StatusTooManyRequests)
			return
		}
		next.ServeHTTP(w, r)
	})
}
