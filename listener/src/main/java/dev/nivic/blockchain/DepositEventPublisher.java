package dev.nivic.blockchain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Publish crypto deposit events to RabbitMQ.
 * Events flow: blockchain-listener → gtel-events exchange → saving-gateway → ledger.
 */
@Service
public class DepositEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(DepositEventPublisher.class);

  @Value("${rabbitmq.exchange:gtel-events}")
  private String exchange;

  @Value("${rabbitmq.routing-key:ledger.crypto}")
  private String routingKey;

  private final RabbitTemplate rabbitTemplate;
  private final ObjectMapper objectMapper;

  public DepositEventPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
    this.rabbitTemplate = rabbitTemplate;
    this.objectMapper = objectMapper;
  }

  /**
   * Publish: CRYPTO_DEPOSIT_INITIATED (pending, awaiting confirmations).
   */
  public void publishDepositInitiated(
      String depositId,
      String cryptoCurrency,
      long cryptoAmountMinor,
      String txHash,
      long blockHeight,
      String fromAddress,
      String toAddress,
      int confirmations,
      long fxRateSnapshot) {

    try {
      Map<String, Object> data = new HashMap<>();
      data.put("deposit_id", depositId);
      data.put("crypto_currency", cryptoCurrency);
      data.put("crypto_amount", cryptoAmountMinor);
      data.put("tx_hash", txHash);
      data.put("block_height", blockHeight);
      data.put("from_address", fromAddress);
      data.put("to_address", toAddress);
      data.put("block_confirmations", confirmations);
      data.put("exchange_rate_snapshot", fxRateSnapshot);

      publishEvent("CRYPTO_DEPOSIT_INITIATED", depositId, data);
      log.info("Published CRYPTO_DEPOSIT_INITIATED: {} {}", depositId, cryptoCurrency);
    } catch (Exception e) {
      log.error("Failed to publish deposit initiated event", e);
    }
  }

  /**
   * Publish: CRYPTO_DEPOSIT_CONFIRMED (finalized, ≥12 confirmations).
   */
  public void publishDepositConfirmed(
      String depositId,
      String cryptoCurrency,
      long cryptoAmountMinor,
      long finalBlockHeight,
      int finalConfirmations) {

    try {
      Map<String, Object> data = new HashMap<>();
      data.put("deposit_id", depositId);
      data.put("crypto_currency", cryptoCurrency);
      data.put("crypto_amount", cryptoAmountMinor);
      data.put("final_block_height", finalBlockHeight);
      data.put("final_confirmations", finalConfirmations);

      publishEvent("CRYPTO_DEPOSIT_CONFIRMED", depositId, data);
      log.info("Published CRYPTO_DEPOSIT_CONFIRMED: {} {}", depositId, cryptoCurrency);
    } catch (Exception e) {
      log.error("Failed to publish deposit confirmed event", e);
    }
  }

  private void publishEvent(String eventType, String depositId, Map<String, Object> data) throws Exception {
    Map<String, Object> event = new HashMap<>();
    event.put("event_id", System.nanoTime()); // Use nanotime as unique ID
    event.put("event_type", eventType);
    event.put("timestamp", System.currentTimeMillis());
    event.put("source", "blockchain-listener");
    event.put("correlation_id", generateCorrelationId(depositId));
    event.put("data", data);
    event.put("retry_count", 0);

    String payload = objectMapper.writeValueAsString(event);

    rabbitTemplate.convertAndSend(exchange, routingKey, payload);
  }

  private long generateCorrelationId(String depositId) {
    return Math.abs(depositId.hashCode() * 31L + System.nanoTime());
  }
}
