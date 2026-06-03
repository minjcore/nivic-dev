package dev.nivic.coa;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Crypto deposit → FX conversion → merchant credit. Posts journal entries directly to ledger.
 * No Spring. No interfaces. Just business logic + data.
 */
public class CryptoDepositProcessor {

  private final FundFlowLedger ledger;
  private final AccountManager accountMgr;

  public CryptoDepositProcessor(FundFlowLedger ledger, AccountManager accountMgr) {
    this.ledger = ledger;
    this.accountMgr = accountMgr;
  }

  /**
   * Step 1: Blockchain event → ledger post.
   * DR 1100-CRYPTO (asset received) / CR 3500-CRYPTO-RECV (transit).
   *
   * Returns: transaction ID for audit.
   */
  public long confirmDeposit(
      String depositId,
      String cryptoCurrency,
      long cryptoAmountMinor,
      String txHash,
      long blockHeight) {

    String assetAccount = mapCryptoToAssetAccount(cryptoCurrency); // "1100-USDT"

    CoaTransLine line1 = new CoaTransLine(
        1,
        assetAccount,
        "Crypto Asset (" + cryptoCurrency + ")",
        cryptoAmountMinor,  // debit
        0,                  // credit
        cryptoCurrency);

    CoaTransLine line2 = new CoaTransLine(
        2,
        "3500-CRYPTO-RECV",
        "Crypto Received (Transit)",
        0,                  // debit
        cryptoAmountMinor,  // credit
        cryptoCurrency);

    String memo = String.format(
        "Crypto deposit confirmed: %s %d %s from tx %s (height %d)",
        depositId, cryptoAmountMinor, cryptoCurrency, txHash, blockHeight);

    CoaTrans trans = new CoaTrans(
        0,  // id = 0; ledger assigns sequence
        depositId,  // refId for idempotency
        memo,
        Instant.now(),
        List.of(line1, line2));

    return ledger.post(trans);
  }

  /**
   * Step 2: FX conversion → 3-line entry.
   * DR 1200 / CR 3500 (clear crypto recv transit)
   * DR 2100 / CR 1200 (accrual to payable)
   * DR 1300 / CR 2100 (post to merchant balance)
   */
  public long convertAndCredit(
      String depositId,
      String cryptoCurrency,
      long cryptoAmountMinor,
      long vndAmountMinor,
      long fxRate,
      String merchantId,
      long custodyFeeMinor) {

    long netCredit = vndAmountMinor - custodyFeeMinor;
    if (netCredit <= 0) throw new IllegalArgumentException("net credit must be > 0");

    // Line 1: FX buffer receives equivalent VND; crypto transit clears
    CoaTransLine line1 = new CoaTransLine(
        1,
        "1200-CRYPTO-FIAT",
        "Crypto/Fiat Exchange Buffer",
        vndAmountMinor,  // debit (receive VND equivalent)
        0,
        "VND");

    CoaTransLine line2 = new CoaTransLine(
        1,
        "3500-CRYPTO-RECV",
        "Crypto Received (Transit)",
        0,
        cryptoAmountMinor,  // credit (clear crypto)
        cryptoCurrency);

    // Line 2: Accrual to merchant payable
    CoaTransLine line3 = new CoaTransLine(
        2,
        "2100-USER-PAYABLE",
        "Merchant Payable (Accrual)",
        netCredit,  // debit (owe merchant)
        0,
        "VND");

    CoaTransLine line4 = new CoaTransLine(
        2,
        "1200-CRYPTO-FIAT",
        "Crypto/Fiat Exchange Buffer",
        0,
        netCredit,  // credit (transfer out)
        "VND");

    // Line 3: Post to merchant balance
    CoaTransLine line5 = new CoaTransLine(
        3,
        "1300-MERCHANT-BAL",
        "Merchant Balance",
        netCredit,  // debit (merchant receives)
        0,
        "VND");

    CoaTransLine line6 = new CoaTransLine(
        3,
        "2100-USER-PAYABLE",
        "Merchant Payable (Accrual)",
        0,
        netCredit,  // credit (settle accrual)
        "VND");

    // Fee posting (if any)
    List<CoaTransLine> lines;
    if (custodyFeeMinor > 0) {
      CoaTransLine feeLine = new CoaTransLine(
          4,
          "5100-CUSTODY-FEE",
          "Custody/Processing Fee",
          custodyFeeMinor,  // debit (expense)
          0,
          "VND");

      CoaTransLine feeCredit = new CoaTransLine(
          4,
          "1200-CRYPTO-FIAT",
          "Crypto/Fiat Exchange Buffer",
          0,
          custodyFeeMinor,  // credit (offset from buffer)
          "VND");

      lines = List.of(line1, line2, line3, line4, line5, line6, feeLine, feeCredit);
    } else {
      lines = List.of(line1, line2, line3, line4, line5, line6);
    }

    String memo = String.format(
        "Crypto conversion: %s %d %s @ %d VND (net credit: %d to merchant %s)",
        depositId, cryptoAmountMinor, cryptoCurrency, fxRate, netCredit, merchantId);

    CoaTrans trans = new CoaTrans(
        0,
        depositId + "-convert",  // idempotency: different key from depositConfirm
        memo,
        Instant.now(),
        lines);

    return ledger.post(trans);
  }

  private String mapCryptoToAssetAccount(String cryptoCurrency) {
    return switch (cryptoCurrency) {
      case "USDT" -> "1100-USDT";
      case "ETH" -> "1101-ETH";
      case "BTC" -> "1102-BTC";
      default -> throw new IllegalArgumentException("unknown crypto: " + cryptoCurrency);
    };
  }
}
