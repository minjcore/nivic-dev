package dev.nivic.ledger;

import java.util.Optional;
import java.util.List;

/**
 * Wallet operations: create, transfer, hold, settle.
 * NO direct blockchain transfers - all via wallet system.
 */
public interface WalletManager {
  // Wallet CRUD
  Wallet createWallet(String uid, String walletType, String currency, String accountCode);
  Optional<Wallet> getWallet(long walletId);
  Optional<Wallet> findByUid(String uid, String currency);
  void updateStatus(long walletId, String status);

  // Transfer: A → B (must go through wallet)
  WalletTransfer initiateTransfer(
      long fromWalletId, long toWalletId,
      long amountMinor, String currency,
      String refId, String memo
  );

  Optional<WalletTransfer> getTransfer(long transferId);
  Optional<WalletTransfer> getTransferByRefId(String refId);

  // Post transfer to ledger (creates journal entries)
  void postTransfer(long transferId, long transactionId);

  // Confirm transfer completion
  void confirmTransfer(long transferId);

  // Hold (freeze balance during transfer)
  void holdBalance(long walletId, long transferId, long amountMinor);
  void releaseHold(long transferId);
  void captureHold(long transferId);

  // Query
  long getAvailableBalance(long walletId);
  long getHeldBalance(long walletId);
  List<WalletTransfer> getPendingTransfers(long walletId);
}
