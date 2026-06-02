package dev.nivic.coa.bridge;

import dev.nivic.coa.FundFlowLedger;
import dev.nivic.sevlet.SevletWalletPayload;
import java.util.Objects;

/**
 * Cầu nối ví vận hành (Sevlet wallet) → sổ cái COA.
 *
 * <p>Mỗi payload ví đã là double-entry sẵn (DR account[debit] / CR account[credit] cho {@code
 * amount}). Bridge phản chiếu nó thành bút toán GL trên control account 2110:
 * {@code DR 2110(party=debit) / CR 2110(party=credit)} — tiền dịch chuyển giữa hai ví trong sổ chi
 * tiết, tổng 2110 không đổi. Sau khi mirror, {@code Σ walletBalance(các party) = số dư 2110}.</p>
 *
 * <p>Gọi từ đường accept (sau khi ví vận hành persist thành công) hoặc từ một listener event để
 * không chặn hot path. Idempotent qua ref {@code "WAL:" + mid + ":" + requestId} — gọi lại an toàn.</p>
 */
public final class WalletGlBridge {

  private final FundFlowLedger gl;

  public WalletGlBridge(FundFlowLedger gl) {
    this.gl = Objects.requireNonNull(gl, "gl");
  }

  /** Phản chiếu một payload ví đã chấp nhận vào sổ cái COA. */
  public void mirror(SevletWalletPayload payload) {
    Objects.requireNonNull(payload, "payload");
    String ref = ref(payload.mid(), payload.requestId());
    gl.mirrorWalletTransfer(
        payload.debit(), payload.credit(), payload.amount(), ref,
        "Mirror ví mid=" + payload.mid() + " req=" + payload.requestId());
  }

  /** Khoá idempotency cho một payload ví (mid + requestId là duy nhất). */
  public static String ref(long mid, long requestId) {
    return "WAL:" + mid + ":" + requestId;
  }
}
