package dev.nivic.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import dev.nivic.bank.BankGateway;
import java.util.*;

@Service
public class SettlementService {

  private final JdbcTemplate jdbcTemplate;
  private final BankGateway bankGateway;
  private volatile boolean tableEnsured = false;

  public SettlementService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    // Initialize standalone BankGateway (no Spring dependency)
    this.bankGateway = new BankGateway(
        System.getenv("SWIFT_ENDPOINT"),
        System.getenv("ACH_ENDPOINT"),
        System.getenv("LOCAL_BANK_ENDPOINT")
    );
    ensureTable();
  }

  private void ensureTable() {
    if (tableEnsured) return;
    try {
      jdbcTemplate.execute("""
          CREATE TABLE IF NOT EXISTS settlement_requests (
            id              BIGINT       PRIMARY KEY,
            currency        VARCHAR(10)  NOT NULL,
            amount_crypto   BIGINT       NOT NULL,
            amount_vnd      BIGINT       NOT NULL DEFAULT 0,
            bank_account    VARCHAR(20)  NOT NULL,
            status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
            created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
          )
          """);
      tableEnsured = true;
    } catch (Exception e) {
      // Table might already exist, that's ok
      tableEnsured = true;
    }
  }

  public SettlementBalance checkBalance(String currency) {
    var balance = jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(debit_minor) - SUM(credit_minor), 0) as balance "
            + "FROM coa_trans_data WHERE account_code = ? AND currency_code = ?",
        new Object[]{"3500", currency},
        (rs, rowNum) -> rs.getLong("balance")
    );

    var fxRate = currency.equals("USDT") ? 25000L : (currency.equals("USDC") ? 25000L : 1L);
    var vndEquivalent = balance / 1000000000000000000L * fxRate;

    return new SettlementBalance(currency, balance, vndEquivalent, fxRate);
  }

  public SettlementRequest initiateSettlement(String currency, long amount, String bankAccount) {
    var id = System.currentTimeMillis();
    var refId = "settlement-" + id;

    jdbcTemplate.update(
        "INSERT INTO settlement_requests (id, currency, amount_crypto, amount_vnd, bank_account, status, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, NOW())",
        id, currency, amount, 0L, bankAccount, "PENDING"
    );

    return new SettlementRequest(id, refId, currency, amount, "PENDING");
  }

  public SettlementRequest getSettlement(long id) {
    try {
      return jdbcTemplate.queryForObject(
          "SELECT id, currency, amount_crypto, amount_vnd, status FROM settlement_requests WHERE id = ?",
          new Object[]{id},
          (rs, rowNum) -> new SettlementRequest(
              rs.getLong("id"),
              "settlement-" + rs.getLong("id"),
              rs.getString("currency"),
              rs.getLong("amount_crypto"),
              rs.getString("status")
          )
      );
    } catch (Exception e) {
      return null;
    }
  }

  public SettlementReceipt executeSettlement(long settlementId) {
    var settlement = getSettlement(settlementId);
    if (settlement == null) return null;

    var fxRate = settlement.currency.equals("USDT") ? 25000L : 25000L;
    var vndAmount = (settlement.amountCrypto / 1000000000000000000L) * fxRate;
    long vndMinor = vndAmount * 100000;

    var lines = new ArrayList<SettlementLine>();
    long transId = System.currentTimeMillis();

    // Combined transaction: Crypto → Transit FX → Bank all in one
    var memo = String.format("Settlement: %s %,.2f → VND %,.0f",
        settlement.currency, settlement.amountCrypto / 1e18, vndAmount);

    try {
      // Insert header
      jdbcTemplate.update(
          "INSERT INTO coa_trans (id, ref_id, memo) VALUES (?, ?, ?)",
          transId, "settlement-" + settlementId, memo
      );

      // Line 1: Debit Transit FX (3510) with crypto
      jdbcTemplate.update(
          "INSERT INTO coa_trans_data (trans_id, line_no, account_code, debit_minor, credit_minor, currency_code) "
              + "VALUES (?, 1, ?, ?, ?, ?)",
          transId, "3510", settlement.amountCrypto, 0L, settlement.currency
      );

      // Line 2: Credit Transit Crypto (3500) with crypto
      jdbcTemplate.update(
          "INSERT INTO coa_trans_data (trans_id, line_no, account_code, debit_minor, credit_minor, currency_code) "
              + "VALUES (?, 2, ?, ?, ?, ?)",
          transId, "3500", 0L, settlement.amountCrypto, settlement.currency
      );

      // Line 3: Debit Bank Account (1111) with VND
      jdbcTemplate.update(
          "INSERT INTO coa_trans_data (trans_id, line_no, account_code, debit_minor, credit_minor, currency_code) "
              + "VALUES (?, 3, ?, ?, ?, ?)",
          transId, "1111", vndMinor, 0L, "VND"
      );

      // Line 4: Credit Transit FX (3510) with VND
      jdbcTemplate.update(
          "INSERT INTO coa_trans_data (trans_id, line_no, account_code, debit_minor, credit_minor, currency_code) "
              + "VALUES (?, 4, ?, ?, ?, ?)",
          transId, "3510", 0L, vndMinor, "VND"
      );

      lines.add(new SettlementLine(1, "3510", settlement.amountCrypto, 0L, settlement.currency));
      lines.add(new SettlementLine(2, "3500", 0L, settlement.amountCrypto, settlement.currency));
      lines.add(new SettlementLine(3, "1111", vndMinor, 0L, "VND"));
      lines.add(new SettlementLine(4, "3510", 0L, vndMinor, "VND"));
    } catch (Exception e) {
      // Return null if settlement fails
      return null;
    }

    // Update settlement status to POSTED (ledger transaction done)
    jdbcTemplate.update(
        "UPDATE settlement_requests SET status = 'POSTED', amount_vnd = ? WHERE id = ?",
        vndAmount, settlementId
    );

    // INITIATE BANK TRANSFER (non-blocking, will complete async)
    try {
      var bankTransfer = bankGateway.initiateTransfer(
          1111L,  // TODO: lookup actual bank account ID from settlement.bankAccount
          vndMinor,
          "VND",
          "Settlement #" + settlementId
      );

      // Execute transfer (to actual bank)
      var transferResult = bankGateway.executeTransfer(bankTransfer.id());

      if (transferResult != null && "EXECUTED".equals(transferResult.status())) {
        // Mark settlement as EXECUTING (bank transfer in progress)
        jdbcTemplate.update(
            "UPDATE settlement_requests SET status = 'EXECUTING' WHERE id = ?",
            settlementId
        );
      }
    } catch (Exception e) {
      // Bank transfer failed, but ledger is posted
      // Mark settlement as FAILED_BANK
      jdbcTemplate.update(
          "UPDATE settlement_requests SET status = 'FAILED_BANK' WHERE id = ?",
          settlementId
      );
    }

    return new SettlementReceipt(transId, settlementId, settlement.currency,
        settlement.amountCrypto, vndAmount, lines);
  }

  public record SettlementBalance(
      String currency,
      long amountCrypto,
      long amountVnd,
      long fxRate
  ) {}

  public record SettlementRequest(
      long id,
      String refId,
      String currency,
      long amountCrypto,
      String status
  ) {}

  public record SettlementLine(
      int lineNo,
      String accountCode,
      long debitMinor,
      long creditMinor,
      String currencyCode
  ) {}

  public record SettlementReceipt(
      long transactionId,
      long settlementId,
      String currency,
      long amountCrypto,
      long amountVnd,
      List<SettlementLine> lines
  ) {}

  // Called by bank webhook when transfer is confirmed
  public void confirmSettlement(long settlementId, String bankTransactionId) {
    // Mark settlement as CONFIRMED (funds arrived at bank account)
    jdbcTemplate.update(
        "UPDATE settlement_requests SET status = 'CONFIRMED' WHERE id = ?",
        settlementId
    );

    // Confirm transfer in bank gateway (for record-keeping)
    // bankGateway.confirmTransfer(transferId, bankTransactionId);
  }
}
