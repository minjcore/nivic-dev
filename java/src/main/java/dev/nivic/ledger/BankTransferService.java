package dev.nivic.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class BankTransferService {

  private final JdbcTemplate jdbcTemplate;

  public BankTransferService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    ensureSchema();
  }

  private void ensureSchema() {
    try {
      jdbcTemplate.execute("""
          CREATE TABLE IF NOT EXISTS bank_accounts (
            id                  BIGINT       PRIMARY KEY,
            account_number      VARCHAR(30)  NOT NULL UNIQUE,
            bank_code           VARCHAR(10)  NOT NULL,
            bank_name           VARCHAR(100) NOT NULL,
            account_holder_name VARCHAR(100) NOT NULL,
            currency            VARCHAR(3)   NOT NULL,
            account_type        VARCHAR(20)  NOT NULL,
            status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING_VERIFICATION',
            created_at          TIMESTAMP    NOT NULL DEFAULT NOW()
          )
          """);

      jdbcTemplate.execute("""
          CREATE TABLE IF NOT EXISTS bank_transfers (
            id                  BIGINT       PRIMARY KEY,
            bank_account_id     BIGINT       NOT NULL REFERENCES bank_accounts(id),
            amount_minor        BIGINT       NOT NULL,
            currency            VARCHAR(3)   NOT NULL,
            status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
            bank_transaction_id VARCHAR(50),
            settlement_id       BIGINT,
            reference_number    VARCHAR(50)  NOT NULL UNIQUE,
            created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
            executed_at         TIMESTAMP,
            confirmed_at        TIMESTAMP
          )
          """);

      jdbcTemplate.execute("""
          CREATE INDEX IF NOT EXISTS bank_transfers_status_idx
          ON bank_transfers(status, created_at DESC)
          """);

      jdbcTemplate.execute("""
          CREATE INDEX IF NOT EXISTS bank_transfers_settlement_idx
          ON bank_transfers(settlement_id)
          """);
    } catch (Exception e) {
      // Tables might exist
    }
  }

  // Register bank account
  public BankAccount registerBankAccount(String accountNumber, String bankCode, String bankName,
      String accountHolder, String currency, String accountType) {
    long id = System.currentTimeMillis();

    jdbcTemplate.update(
        "INSERT INTO bank_accounts (id, account_number, bank_code, bank_name, account_holder_name, currency, account_type) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
        id, accountNumber, bankCode, bankName, accountHolder, currency, accountType
    );

    return new BankAccount(id, accountNumber, bankCode, bankName, accountHolder, currency, accountType, "PENDING_VERIFICATION", null);
  }

  // Get bank account
  public BankAccount getBankAccount(long id) {
    try {
      return jdbcTemplate.queryForObject(
          "SELECT id, account_number, bank_code, bank_name, account_holder_name, currency, account_type, status, created_at "
              + "FROM bank_accounts WHERE id = ?",
          new Object[]{id},
          (rs, rowNum) -> new BankAccount(
              rs.getLong("id"),
              rs.getString("account_number"),
              rs.getString("bank_code"),
              rs.getString("bank_name"),
              rs.getString("account_holder_name"),
              rs.getString("currency"),
              rs.getString("account_type"),
              rs.getString("status"),
              rs.getObject("created_at", java.sql.Timestamp.class).toInstant()
          )
      );
    } catch (Exception e) {
      return null;
    }
  }

  // Activate bank account
  public void activateBankAccount(long id) {
    jdbcTemplate.update("UPDATE bank_accounts SET status = 'ACTIVE' WHERE id = ?", id);
  }

  // Initiate transfer to bank
  public BankTransfer initiateTransfer(long bankAccountId, long amountMinor, String currency, long settlementId) {
    long transferId = System.currentTimeMillis();
    String refNum = "BANK-" + transferId;

    jdbcTemplate.update(
        "INSERT INTO bank_transfers (id, bank_account_id, amount_minor, currency, settlement_id, reference_number) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        transferId, bankAccountId, amountMinor, currency, settlementId, refNum
    );

    return new BankTransfer(transferId, bankAccountId, amountMinor, currency, "PENDING", refNum);
  }

  // Execute bank transfer (simulated - in production would call bank API)
  public BankTransferReceipt executeTransfer(long transferId) {
    var transfer = getTransfer(transferId);
    if (transfer == null) return null;

    var account = getBankAccount(transfer.bankAccountId);
    if (account == null) return null;

    // In production: call bank API (SWIFT, ACH, etc.)
    // For now: simulate successful transfer
    String bankTxId = "SWIFT-" + System.currentTimeMillis();

    // Mark as executed
    jdbcTemplate.update(
        "UPDATE bank_transfers SET status = 'EXECUTED', bank_transaction_id = ?, executed_at = NOW() WHERE id = ?",
        bankTxId, transferId
    );

    return new BankTransferReceipt(
        transferId,
        bankTxId,
        transfer.bankAccountId(),
        account.accountNumber(),
        account.bankName(),
        transfer.amountMinor(),
        transfer.currency(),
        "EXECUTED",
        transfer.referenceNumber()
    );
  }

  // Confirm transfer received (webhook from bank)
  public void confirmTransfer(long transferId) {
    jdbcTemplate.update(
        "UPDATE bank_transfers SET status = 'CONFIRMED', confirmed_at = NOW() WHERE id = ?",
        transferId
    );
  }

  // Get transfer
  public BankTransfer getTransfer(long transferId) {
    try {
      return jdbcTemplate.queryForObject(
          "SELECT id, bank_account_id, amount_minor, currency, status, reference_number "
              + "FROM bank_transfers WHERE id = ?",
          new Object[]{transferId},
          (rs, rowNum) -> new BankTransfer(
              rs.getLong("id"),
              rs.getLong("bank_account_id"),
              rs.getLong("amount_minor"),
              rs.getString("currency"),
              rs.getString("status"),
              rs.getString("reference_number")
          )
      );
    } catch (Exception e) {
      return null;
    }
  }

  // List pending transfers
  public List<BankTransfer> getPendingTransfers() {
    return jdbcTemplate.query(
        "SELECT id, bank_account_id, amount_minor, currency, status, reference_number "
            + "FROM bank_transfers WHERE status IN ('PENDING', 'EXECUTED') ORDER BY created_at DESC LIMIT 100",
        (rs, rowNum) -> new BankTransfer(
            rs.getLong("id"),
            rs.getLong("bank_account_id"),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            rs.getString("status"),
            rs.getString("reference_number")
        )
    );
  }

  public record BankTransfer(
      long id,
      long bankAccountId,
      long amountMinor,
      String currency,
      String status,
      String referenceNumber
  ) {}

  public record BankTransferReceipt(
      long transferId,
      String bankTransactionId,
      long bankAccountId,
      String accountNumber,
      String bankName,
      long amountMinor,
      String currency,
      String status,
      String referenceNumber
  ) {}
}
