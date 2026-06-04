package dev.nivic.bank;

import java.util.*;
import java.time.Instant;

/**
 * Standalone Bank Integration Gateway (không phụ thuộc Spring)
 * - Transfer execution (SWIFT/ACH/Local)
 * - Account management
 * - Status tracking
 */
public class BankGateway {

  private final Map<Long, BankAccount> accounts = new HashMap<>();
  private final Map<Long, BankTransfer> transfers = new HashMap<>();
  private final BankAPIClient apiClient;

  public BankGateway(String swiftEndpoint, String achEndpoint, String localBankEndpoint) {
    this.apiClient = new BankAPIClient(swiftEndpoint, achEndpoint, localBankEndpoint);
  }

  // Register account
  public BankAccount registerAccount(String accountNumber, String bankCode, String bankName,
      String accountHolder, String currency, String accountType) {
    long id = System.currentTimeMillis();
    var account = new BankAccount(
        id, accountNumber, bankCode, bankName, accountHolder, currency, accountType,
        "PENDING_VERIFICATION", Instant.now()
    );
    accounts.put(id, account);
    return account;
  }

  // Activate account
  public void activateAccount(long accountId) {
    var account = accounts.get(accountId);
    if (account != null) {
      accounts.put(accountId, new BankAccount(
          account.id(), account.accountNumber(), account.bankCode(), account.bankName(),
          account.accountHolder(), account.currency(), account.accountType(),
          "ACTIVE", account.createdAt()
      ));
    }
  }

  // Get account
  public BankAccount getAccount(long accountId) {
    return accounts.get(accountId);
  }

  // Initiate transfer (standalone, no Spring)
  public BankTransfer initiateTransfer(long accountId, long amountMinor, String currency,
      String reason) {
    long transferId = System.currentTimeMillis();
    var transfer = new BankTransfer(
        transferId, accountId, amountMinor, currency,
        "PENDING", "TXN-" + transferId, reason, Instant.now()
    );
    transfers.put(transferId, transfer);
    return transfer;
  }

  // Execute transfer directly (no REST, direct method)
  public TransferResult executeTransfer(long transferId) {
    var transfer = transfers.get(transferId);
    if (transfer == null) return null;

    var account = accounts.get(transfer.accountId());
    if (account == null) return null;

    try {
      // Call bank API directly
      var result = apiClient.executeTransfer(
          account.accountNumber(),
          account.bankCode(),
          transfer.amountMinor(),
          transfer.currency(),
          transfer.referenceNumber(),
          transfer.reason()
      );

      // Update transfer status
      transfers.put(transferId, new BankTransfer(
          transfer.id(), transfer.accountId(), transfer.amountMinor(), transfer.currency(),
          "EXECUTED", transfer.referenceNumber(), transfer.reason(), transfer.createdAt()
      ));

      return result;
    } catch (Exception e) {
      return new TransferResult(
          transferId, null, "FAILED",
          "Transfer failed: " + e.getMessage()
      );
    }
  }

  // Confirm transfer (webhook callback from bank)
  public void confirmTransfer(long transferId, String bankTxId) {
    var transfer = transfers.get(transferId);
    if (transfer != null) {
      transfers.put(transferId, new BankTransfer(
          transfer.id(), transfer.accountId(), transfer.amountMinor(), transfer.currency(),
          "CONFIRMED", bankTxId, transfer.reason(), transfer.createdAt()
      ));
    }
  }

  // List pending transfers
  public List<BankTransfer> getPendingTransfers() {
    return transfers.values().stream()
        .filter(t -> "PENDING".equals(t.status()) || "EXECUTED".equals(t.status()))
        .toList();
  }

  // Records (no Spring annotations needed)
  public record BankAccount(
      long id,
      String accountNumber,
      String bankCode,
      String bankName,
      String accountHolder,
      String currency,
      String accountType,
      String status,
      Instant createdAt
  ) {}

  public record BankTransfer(
      long id,
      long accountId,
      long amountMinor,
      String currency,
      String status,
      String referenceNumber,
      String reason,
      Instant createdAt
  ) {}

  public record TransferResult(
      long transferId,
      String bankTransactionId,
      String status,
      String message
  ) {}

  // Bank API Client (callable directly)
  public static class BankAPIClient {
    private final String swiftEndpoint;
    private final String achEndpoint;
    private final String localEndpoint;

    public BankAPIClient(String swiftEndpoint, String achEndpoint, String localEndpoint) {
      this.swiftEndpoint = swiftEndpoint;
      this.achEndpoint = achEndpoint;
      this.localEndpoint = localEndpoint;
    }

    // Execute transfer via appropriate channel
    public TransferResult executeTransfer(String accountNumber, String bankCode,
        long amountMinor, String currency, String refNumber, String reason) throws Exception {

      if (bankCode.equals("SWIFT")) {
        return executeSWIFT(accountNumber, amountMinor, currency, refNumber);
      } else if (bankCode.equals("ACH")) {
        return executeACH(accountNumber, amountMinor, currency, refNumber);
      } else {
        return executeLocal(accountNumber, bankCode, amountMinor, currency, refNumber);
      }
    }

    private TransferResult executeSWIFT(String accountNumber, long amountMinor, String currency,
        String refNumber) {
      // TODO: Call real SWIFT API
      return new TransferResult(
          System.currentTimeMillis(),
          "SWIFT-" + refNumber,
          "EXECUTED",
          "SWIFT transfer initiated"
      );
    }

    private TransferResult executeACH(String accountNumber, long amountMinor, String currency,
        String refNumber) {
      // TODO: Call real ACH API
      return new TransferResult(
          System.currentTimeMillis(),
          "ACH-" + refNumber,
          "EXECUTED",
          "ACH transfer initiated"
      );
    }

    private TransferResult executeLocal(String accountNumber, String bankCode, long amountMinor,
        String currency, String refNumber) {
      // TODO: Call local bank API (Vietcombank, MB, etc.)
      return new TransferResult(
          System.currentTimeMillis(),
          bankCode + "-" + refNumber,
          "EXECUTED",
          "Local transfer initiated"
      );
    }
  }
}
