package dev.nivic.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class AuthService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthService.class);


    @Value("${gateway.api-keys:c-server-1=secret1,c-server-2=secret2}")
    private String apiKeysConfig;

    private Map<String, String> apiKeys;

    /**
     * Validate API key from Authorization header
     * Header format: "Bearer <api_key>"
     * Returns client ID if valid, null if invalid
     */
    public String validateApiKey(String authHeader) {
        if (authHeader == null || authHeader.isEmpty()) {
            log.warn("Missing Authorization header");
            return null;
        }

        if (!authHeader.startsWith("Bearer ")) {
            log.warn("Invalid Authorization header format");
            return null;
        }

        String apiKey = authHeader.substring("Bearer ".length()).trim();

        // Load API keys from config (lazy init)
        if (apiKeys == null) {
            loadApiKeys();
        }

        // Find client ID for this API key
        for (Map.Entry<String, String> entry : apiKeys.entrySet()) {
            if (entry.getValue().equals(apiKey)) {
                return entry.getKey();
            }
        }

        log.warn("Invalid API key");
        return null;
    }

    /**
     * Load API keys from configuration
     * Format: "client-1=key1,client-2=key2"
     */
    private synchronized void loadApiKeys() {
        if (apiKeys != null) {
            return;
        }

        apiKeys = new HashMap<>();

        if (apiKeysConfig == null || apiKeysConfig.isEmpty()) {
            log.warn("No API keys configured");
            return;
        }

        String[] pairs = apiKeysConfig.split(",");
        for (String pair : pairs) {
            String[] parts = pair.trim().split("=");
            if (parts.length == 2) {
                String clientId = parts[0].trim();
                String key = parts[1].trim();
                apiKeys.put(clientId, key);
                log.info("Registered API key for client: {}", clientId);
            }
        }

        log.info("Loaded {} API keys", apiKeys.size());
    }

    /**
     * Register a new API key (runtime)
     */
    public void registerClient(String clientId, String apiKey) {
        if (apiKeys == null) {
            loadApiKeys();
        }

        apiKeys.put(clientId, apiKey);
        log.info("Registered API key for client: {}", clientId);
    }

    /**
     * Check if client is registered
     */
    public boolean isClientRegistered(String clientId) {
        if (apiKeys == null) {
            loadApiKeys();
        }

        return apiKeys.containsKey(clientId);
    }
}
