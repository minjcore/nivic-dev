package dev.nivic.coa.error;

/**
 * Ném khi một bút toán sẽ đẩy số dư tài khoản sang chiều không hợp lệ (vd ví/transit âm),
 * bị chặn bởi CHECK constraint {@code coa_account_balance_chk} ở tầng DB.
 *
 * <p>Đây là lưới an toàn (defense-in-depth) cho các race TOCTOU vượt qua guard ở tầng Java
 * (InsufficientWallet/Transit/Escrow). Khi xảy ra nghĩa là một thao tác đồng thời đã thay đổi
 * số dư giữa lúc kiểm tra và lúc ghi.</p>
 */
public final class NegativeBalanceException extends RuntimeException {

  public NegativeBalanceException(String message) {
    super(message);
  }
}
