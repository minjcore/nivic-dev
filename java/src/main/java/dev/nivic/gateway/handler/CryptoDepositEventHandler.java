package dev.nivic.gateway.handler;

import dev.nivic.coa.CryptoDepositProcessor;
import dev.nivic.coa.FundFlowLedger;
import dev.nivic.gateway.model.LedgerEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Map;

/**
 * Handle crypto deposit events: CRYPTO_DEPOSIT_CONFIRMED, CRYPTO_DEPOSIT_CONVERTED.
 * Plugs into the event pipeline: Blockchain Listener → RabbitMQ → This Handler → Ledger.
 */
@Component
public class CryptoDepositEventHandler {

  private final CryptoDepositProcessor cryptoProcessor;
  private final FundFlowLedger ledger;

  @Autowired
  public CryptoDepositEventHandler(FundFlowLedger ledger) {
    this.ledger = ledger;
    this.cryptoProcessor = new CryptoDepositProcessor(ledger, ledger.accountManager());
  }

  public void handle(LedgerEvent event) {
    String eventType = event.getEventType();

    if ("CRYPTO_DEPOSIT_CONFIRMED".equals(eventType)) {
      handleConfirmed(event);
    } else if ("CRYPTO_DEPOSIT_CONVERTED".equals(eventType)) {
      handleConverted(event);
    }
  }

  private void handleConfirmed(LedgerEvent event) {
    Map<String, Object> data = event.getData();

    String depositId = (String) data.get("deposit_id");
    String cryptoCurrency = (String) data.get("crypto_currency");
    Number cryptoAmount = (Number) data.get("crypto_amount");
    String txHash = (String) data.get("tx_hash");
    Number blockHeight = (Number) data.get("block_height");

    long transId = cryptoProcessor.confirmDeposit(
        depositId,
        cryptoCurrency,
        cryptoAmount.longValue(),
        txHash,
        blockHeight.longValue());

    System.out.printf(
        "[Crypto] Deposit confirmed: %s (%s) → transaction %d%n",
        depositId, cryptoCurrency, transId);
  }

  private void handleConverted(LedgerEvent event) {
    Map<String, Object> data = event.getData();

    String depositId = (String) data.get("deposit_id");
    String cryptoCurrency = (String) data.get("crypto_currency");
    Number cryptoAmount = (Number) data.get("crypto_amount");
    Number vndAmount = (Number) data.get("vnd_amount");
    Number fxRate = (Number) data.get("exchange_rate_used");
    Number fee = (Number) data.get("custody_fee_minor");
    String merchantId = (String) data.get("merchant_id");

    long transId = cryptoProcessor.convertAndCredit(
        depositId,
        cryptoCurrency,
        cryptoAmount.longValue(),
        vndAmount.longValue(),
        fxRate.longValue(),
        merchantId != null ? merchantId : "default-merchant",
        fee != null ? fee.longValue() : 0L);

    System.out.printf(
        "[Crypto] Converted: %s (%d %s → %d VND) → transaction %d%n",
        depositId, cryptoAmount.longValue(), cryptoCurrency, vndAmount.longValue(), transId);
  }
}
