package dev.nivic.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.nivic.gateway.model.LedgerEvent;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class EventEnricher {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EventEnricher.class);


    /**
     * Enrich event with additional context
     * - User tier
     * - Merchant category
     * - Fraud score
     * etc.
     */
    public void enrich(LedgerEvent event) {
        try {
            Map<String, Object> data = event.getData();

            // Add user tier (if user_id present)
            if (event.getUserId() != null) {
                String userTier = getUserTier(event.getUserId());
                if (userTier != null) {
                    data.put("user_tier", userTier);
                }
            }

            // Add merchant category (if merchant_id in data)
            if (data.containsKey("merchant_id")) {
                String merchantId = (String) data.get("merchant_id");
                String merchantCategory = getMerchantCategory(merchantId);
                if (merchantCategory != null) {
                    data.put("merchant_category", merchantCategory);
                }
            }

            // Add gateway enrichment metadata
            data.put("gateway_received_at", event.getGatewayReceivedAt());
            data.put("source_c_server", event.getSourceCServer());

        } catch (Exception e) {
            log.warn("Error enriching event: event_id={}", event.getEventId(), e);
            // Non-fatal: continue processing if enrichment fails
        }
    }

    /**
     * Get user tier from cache or DB
     * TODO: implement user service integration
     */
    private String getUserTier(String userId) {
        // Placeholder: in production, query user service/cache
        return "STANDARD";
    }

    /**
     * Get merchant category from cache or DB
     * TODO: implement merchant service integration
     */
    private String getMerchantCategory(String merchantId) {
        // Placeholder: in production, query merchant service/cache
        return "5411";  // Default: grocery stores
    }
}
