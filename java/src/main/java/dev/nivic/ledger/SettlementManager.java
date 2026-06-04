package dev.nivic.ledger;

import java.util.Optional;
import java.util.List;

/**
 * Settlement manager: Wallet → Bank/Blockchain conversion.
 * State: PENDING → HOLD → POSTED → EXECUTING → CONFIRMED (or FAILED_BANK)
 */
public interface SettlementManager {
  // Initiate settlement (creates entry, status=PENDING)
  Settlement initiate(long walletId, long amountMinor, String type, String currency, String destination);

  // Hold wallet balance (prevents double-settlement)
  void hold(long settlementId);

  // Post to ledger (creates journal entries)
  void post(long settlementId, long transactionId);

  // Execute settlement (initiate bank/blockchain transfer)
  void execute(long settlementId, String bankTransactionId) throws SettlementException;

  // Confirm settlement (bank/blockchain callback)
  void confirm(long settlementId, String bankTransactionId);

  // Failure handling (release hold, restore balance)
  void fail(long settlementId, String reason);

  // Retry failed settlement
  void retry(long settlementId);

  // Query
  Optional<Settlement> get(long settlementId);
  List<Settlement> listPending();
  List<Settlement> listByWallet(long walletId);
  List<Settlement> listByStatus(String status);
}

class SettlementException extends Exception {
  public SettlementException(String msg) { super(msg); }
  public SettlementException(String msg, Throwable cause) { super(msg, cause); }
}
