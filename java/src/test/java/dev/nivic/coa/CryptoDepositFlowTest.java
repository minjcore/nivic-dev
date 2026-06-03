package dev.nivic.coa;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

/**
 * End-to-end crypto deposit flow:
 * 1. Blockchain event: 1000 USDT received (blockheight 18500000)
 * 2. Ledger posts: DR 1100-USDT / CR 3500-CRYPTO-RECV
 * 3. FX conversion @ 24,500 VND/USDT → 24,500,000 VND
 * 4. Ledger posts: 3-line entry (1200 → 3500 clear, 2100 accrual, 1300 credit)
 * 5. Verify transit clears, merchant balance increases
 */
public class CryptoDepositFlowTest {

  private InMemoryLedger ledger = new InMemoryLedger();
  private CryptoDepositProcessor crypto = new CryptoDepositProcessor(ledger, ledger);

  @Test
  void testCryptoDepositAndConversion() {
    // Setup: Initialize accounts
    ledger.setupAccount("1100-USDT", "Crypto Asset USDT", CoaAccountKind.ASSET, "USDT");
    ledger.setupAccount("1200-CRYPTO-FIAT", "Exchange Buffer", CoaAccountKind.ASSET, "VND");
    ledger.setupAccount("1300-MERCHANT-BAL", "Merchant Balance", CoaAccountKind.ASSET, "VND");
    ledger.setupAccount("2100-USER-PAYABLE", "Merchant Payable", CoaAccountKind.LIABILITY, "VND");
    ledger.setupAccount("3500-CRYPTO-RECV", "Crypto Received (Transit)", CoaAccountKind.TRANSIT, "USDT");
    ledger.setupAccount("5100-CUSTODY-FEE", "Custody Fee", CoaAccountKind.EXPENSE, "VND");

    // Step 1: Blockchain event detected
    long deposit1 = crypto.confirmDeposit(
        "dep-001-usdt",
        "USDT",
        1000_000_000,  // 1000 USDT
        "0xabc123...",
        18500000L);

    System.out.println("✓ Step 1: Deposit confirmed, transaction ID = " + deposit1);

    // Verify Step 1 posting
    long usdt1100 = ledger.getBalance("1100-USDT", "USDT");
    long transit3500 = ledger.getBalance("3500-CRYPTO-RECV", "USDT");

    assertEquals(1000_000_000, usdt1100, "Asset account should have +1000 USDT");
    assertEquals(-1000_000_000, transit3500, "Transit should have -1000 USDT");
    System.out.println("  → 1100-USDT balance: " + usdt1100);
    System.out.println("  → 3500-CRYPTO-RECV balance: " + transit3500);

    // Step 2: FX conversion
    long deposit2 = crypto.convertAndCredit(
        "dep-001-usdt",
        "USDT",
        1000_000_000,
        24_500_000_000L,  // 24,500,000 VND
        24_500,           // FX rate: 1 USDT = 24,500 VND
        "merchant-abc",
        0);               // No custody fee

    System.out.println("✓ Step 2: Converted to fiat, transaction ID = " + deposit2);

    // Verify Step 2 posting
    long transit3500After = ledger.getBalance("3500-CRYPTO-RECV", "USDT");
    long merchant1300 = ledger.getBalance("1300-MERCHANT-BAL", "VND");
    long payable2100 = ledger.getBalance("2100-USER-PAYABLE", "VND");
    long buffer1200 = ledger.getBalance("1200-CRYPTO-FIAT", "VND");

    assertEquals(0, transit3500After, "Transit 3500 should be cleared");
    assertEquals(24_500_000_000L, merchant1300, "Merchant balance should be +24,500,000 VND");
    assertEquals(-24_500_000_000L, payable2100, "Payable accrual should be negative (credit-normal)");
    assertEquals(0, buffer1200, "Buffer should be clear (debit then credit out)");

    System.out.println("  → 3500-CRYPTO-RECV cleared: " + transit3500After);
    System.out.println("  → 1300-MERCHANT-BAL credited: " + merchant1300);
    System.out.println("  → 2100-USER-PAYABLE accrual: " + payable2100);

    System.out.println("\n✅ Crypto deposit flow verified: 1000 USDT → 24,500,000 VND");
  }

  @Test
  void testCryptoWithCustodyFee() {
    ledger.setupAccount("1100-USDT", "Crypto Asset USDT", CoaAccountKind.ASSET, "USDT");
    ledger.setupAccount("1200-CRYPTO-FIAT", "Exchange Buffer", CoaAccountKind.ASSET, "VND");
    ledger.setupAccount("1300-MERCHANT-BAL", "Merchant Balance", CoaAccountKind.ASSET, "VND");
    ledger.setupAccount("2100-USER-PAYABLE", "Merchant Payable", CoaAccountKind.LIABILITY, "VND");
    ledger.setupAccount("3500-CRYPTO-RECV", "Crypto Received (Transit)", CoaAccountKind.TRANSIT, "USDT");
    ledger.setupAccount("5100-CUSTODY-FEE", "Custody Fee", CoaAccountKind.EXPENSE, "VND");

    // Deposit 500 USDT
    crypto.confirmDeposit(
        "dep-002-usdt",
        "USDT",
        500_000_000,
        "0xdef456...",
        18500001L);

    // Convert with 1% custody fee
    long vndAmount = 12_250_000_000L;  // 500 USDT @ 24,500
    long custodyFee = 122_500_000L;    // 1% fee
    long netCredit = vndAmount - custodyFee;

    crypto.convertAndCredit(
        "dep-002-usdt",
        "USDT",
        500_000_000,
        vndAmount,
        24_500,
        "merchant-xyz",
        custodyFee);

    long merchant1300 = ledger.getBalance("1300-MERCHANT-BAL", "VND");
    long fee5100 = ledger.getBalance("5100-CUSTODY-FEE", "VND");

    assertEquals(netCredit, merchant1300, "Merchant receives net of fee");
    assertEquals(custodyFee, fee5100, "Custody fee expensed");

    System.out.println("✅ Custody fee flow verified: 500 USDT → " + netCredit + " VND (fee: " + custodyFee + ")");
  }
}

// Minimal in-memory ledger for testing
class InMemoryLedger implements FundFlowLedger {

  private Map<String, Long> balances = new HashMap<>();
  private List<CoaTrans> transactions = new ArrayList<>();
  private long transId = 1;

  void setupAccount(String code, String name, CoaAccountKind kind, String currency) {
    balances.put(code + ":" + currency, 0L);
  }

  long getBalance(String account, String currency) {
    return balances.getOrDefault(account + ":" + currency, 0L);
  }

  @Override
  public long post(CoaTrans trans) {
    // Apply the transaction to all lines
    for (CoaTransLine line : trans.lines()) {
      String key = line.accountCode() + ":" + line.currencyCode();
      long current = balances.getOrDefault(key, 0L);
      long delta = line.netDelta();  // debit - credit
      balances.put(key, current + delta);
    }

    CoaTrans posted = new CoaTrans(transId, trans.refId(), trans.memo(), trans.createdAt(), trans.lines());
    transactions.add(posted);
    return transId++;
  }

  @Override
  public AccountManager accountManager() {
    return null;  // Stub for test
  }
}
