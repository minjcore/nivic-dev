package dev.nivic.payment;

import dev.nivic.money.Money;
import dev.nivic.money.MoneyTransfers;
import dev.nivic.sevlet.SevletWalletPayload;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * <strong>Wallet</strong> domain helper: maps a verified {@link SevletWalletPayload} to {@link
 * MoneyTransfers} (in-memory balance math). For <strong>persistence</strong>, use {@link
 * LedgerService} / {@link WalService} via {@link WalletAcceptService}.
 *
 * <p><strong>Ví dụ</strong> — chuyển đúng số tiền trên wire từ số dư “tài khoản debit” sang “tài khoản
 * credit” (bạn tự load/save balance theo {@link SevletWalletPayload#debit()} / {@link
 * SevletWalletPayload#credit()}):</p>
 *
 * <pre>{@code
 * Currency vnd = Currency.getInstance("VND");
 * WalletService wallet = new WalletService(vnd);
 *
 * Money nganHang = Money.ofMajor(vnd, new BigDecimal("1_000_000"));
 * Money viKhach = Money.zero(vnd);
 *
 * // payload: amount = số minor, debit/credit = id tài khoản (chỉ để đối chiếu khi load DB)
 * MoneyTransfers.Balances after = wallet.transfer(payload, nganHang, viKhach);
 *
 * Money remainNganHang = after.balanceA(); // đã trừ amount
 * Money newViKhach = after.balanceB();    // đã cộng amount
 * }</pre>
 *
 * <p>Wire fields {@code debit} / {@code credit} are account <em>ids</em>; this class does not resolve
 * them — pass balances for the two legs in the same order as {@link MoneyTransfers#transfer} (from
 * A to B).</p>
 *
 * <p>For {@link dev.nivic.command.WalletInputCommand} routing, transfers usually use
 * {@link SevletWalletPayload#command()} {@code ==} {@link dev.nivic.command.WalletInputOp#TRANSFER}.</p>
 */
public final class WalletService {

  private final Currency ledgerCurrency;

  public WalletService(Currency ledgerCurrency) {
    this.ledgerCurrency = Objects.requireNonNull(ledgerCurrency, "ledgerCurrency");
  }

  public Currency ledgerCurrency() {
    return ledgerCurrency;
  }

  /**
   * Moves {@link SevletWalletPayload#amountAsMoney(Currency) payload amount} from {@code
   * balanceDebitAccount} to {@code balanceCreditAccount}.
   *
   * @see MoneyTransfers#transfer(Money, Money, Money)
   */
  public MoneyTransfers.Balances transfer(
      SevletWalletPayload payload, Money balanceDebitAccount, Money balanceCreditAccount) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(balanceDebitAccount, "balanceDebitAccount");
    Objects.requireNonNull(balanceCreditAccount, "balanceCreditAccount");
    Money amount = payload.amountAsMoney(ledgerCurrency);
    return MoneyTransfers.transfer(balanceDebitAccount, balanceCreditAccount, amount);
  }
}
