package dev.nivic.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
public class CryptoDepositListener {
    private static final Logger log = LoggerFactory.getLogger(CryptoDepositListener.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @RabbitListener(queues = "crypto.deposits")
    public void handleCryptoDeposit(String message) {
        try {
            JsonNode event = mapper.readTree(message);

            String eventType = event.get("event_type").asText();
            if (!"CRYPTO_DEPOSIT_INITIATED".equals(eventType)) {
                return;
            }

            JsonNode data = event.get("data");
            String depositId = data.get("deposit_id").asText();
            String currency = data.get("crypto_currency").asText();
            String amount = data.get("crypto_amount").asText();
            String txHash = data.get("tx_hash").asText();
            long blockHeight = data.get("block_height").asLong();

            log.info("Processing CRYPTO_DEPOSIT_INITIATED: {} {} {} tx={} block={}",
                depositId, amount, currency, txHash, blockHeight);

            // TODO: Post ledger entries
            // Step 1: DR 1100-CRYPTO / CR 3500-CRYPTO-RECV
            // Step 2-4: FX conversion and credit
        } catch (Exception e) {
            log.error("Error processing crypto deposit event", e);
        }
    }
}
