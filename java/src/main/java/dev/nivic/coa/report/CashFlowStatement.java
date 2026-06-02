package dev.nivic.coa.report;

/**
 * Báo cáo lưu chuyển tiền tệ phân loại theo 3 hoạt động (chuẩn quản trị):
 * <ul>
 *   <li><b>Operating</b> (kinh doanh) — luồng tiền cốt lõi: nạp/rút/thanh toán/phí/FX.</li>
 *   <li><b>Investing</b> (đầu tư) — mua/bán tài sản, đầu tư (chưa phát sinh ⇒ 0).</li>
 *   <li><b>Financing</b> (tài chính) — nạp/rút vốn (đối ứng tài khoản Vốn 6xxx).</li>
 * </ul>
 *
 * <p>Cash = tài khoản ngân hàng (mã 11xx). Phân loại mỗi giao dịch chạm tiền theo tài khoản
 * đối ứng. {@code netCashFlow = operating + investing + financing = closingCash − openingCash}.</p>
 */
public record CashFlowStatement(
    long operating,
    long investing,
    long financing,
    long openingCash,
    long closingCash) {

  public long netCashFlow() { return operating + investing + financing; }

  /** opening + net = closing (luôn đúng vì cùng nguồn bút toán). */
  public boolean isConsistent() { return openingCash + netCashFlow() == closingCash; }
}
