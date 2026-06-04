package dev.nivic.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nivic.coa.FundFlowLedger;
import dev.nivic.coa.cmd.CryptoDepositCmd;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.math.BigInteger;

/**
 * Consumes CRYPTO_DEPOSIT_INITIATED events and posts journal entries.
 *
 * Flow:
 *   Step 1: Crypto received on-chain
 *     DR 1100-CRYPTO / CR 3500-CRYPTO-RECV
 *
 *   Step 2-4: FX conversion and merchant credit
 *     DR 1200-CONVERSION / CR 3500-CRYPTO-RECV
 *     DR 2100-MERCHANT-PAYABLE / CR 1200-CONVERSION
 *     DR 1300-MERCHANT-WALLET / CR 2100-MERCHANT-PAYABLE
 *
 * Account Codes (per CRYPTO_TOPUP_FLOW.md):
 *   1100 = Cryptocurrency received (ASSET)
 *   1200 = FX conversion account (ASSET)
 *   1300 = Merchant wallet (ASSET)
 *   2100 = Merchant payable (LIABILITY)
 *   3500 = Transit - crypto receive (TRANSIT)
 */
@Service
public class CryptoDepositListener {
    private static final Logger log = LoggerFactory.getLogger(CryptoDepositListener.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private FundFlowLedger ledger;

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
            String amountHex = data.get("crypto_amount").asText();
            String txHash = data.get("tx_hash").asText();
            long blockHeight = data.get("block_height").asLong();

            // Parse hex amount to long (e.g., 0x000...000 → amount in wei/satoshi)
            long amount = parseHexAmount(amountHex);

            log.info("Processing CRYPTO_DEPOSIT_INITIATED: id={} currency={} amount={} tx={} block={}",
                depositId, currency, amount, txHash, blockHeight);

            // Idempotency key: deposit-{depositId}
            String refId = "crypto-" + depositId;

            // Step 1: Receive crypto on-chain
            // DR 1100 (Crypto Received) / CR 3500 (Transit)
            CryptoDepositCmd cryptoCmd = new CryptoDepositCmd(refId, amount, currency, txHash, blockHeight);
            var trans = ledger.postCryptoDeposit(cryptoCmd);

            log.info("✓ Posted crypto deposit: {} {} (trans_id={}, ref={})", amount, currency, trans.id(), refId);

        } catch (Exception e) {
            log.error("Error processing crypto deposit event: {}", e.getMessage(), e);
            if (e.getCause() != null) {
                log.error("Caused by: {}", e.getCause().getMessage());
            }
        }
    }

    /**
     * Parse hex-encoded amount (e.g., "0x000...000") to long.
     * Handles both 0x-prefixed and raw hex strings.
     */
    private long parseHexAmount(String hex) {
        try {
            // Remove 0x prefix if present
            String cleanHex = hex.startsWith("0x") ? hex.substring(2) : hex;

            // Handle empty or overflow
            if (cleanHex.isEmpty()) return 0;
            if (cleanHex.length() > 16) {
                log.warn("Amount hex too long (>64 bits), truncating: {}", hex);
                cleanHex = cleanHex.substring(cleanHex.length() - 16);
            }

            return new BigInteger(cleanHex, 16).longValue();
        } catch (NumberFormatException e) {
            log.error("Invalid hex amount format: {}", hex);
            throw new IllegalArgumentException("Invalid hex amount: " + hex, e);
        }
    }
}
