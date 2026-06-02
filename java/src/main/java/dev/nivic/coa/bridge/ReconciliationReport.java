package dev.nivic.coa.bridge;

import java.util.List;
import java.util.Objects;

/**
 * Kết quả đối soát ví vận hành (led_wallet) ↔ sổ cái COA (mirror coa_trans ref WAL:mid:req).
 */
public record ReconciliationReport(
    long operationalCount,
    long mirroredCount,
    long repairedCount,
    List<Missing> missing) {

  public ReconciliationReport {
    Objects.requireNonNull(missing, "missing");
    missing = List.copyOf(missing);
  }

  /** Đã đồng bộ khi không còn bản ghi vận hành thiếu mirror trên GL. */
  public boolean inSync() { return missing.isEmpty(); }

  public long missingCount() { return missing.size(); }

  /** Một bản ghi ví vận hành chưa được phản chiếu sang sổ cái. */
  public record Missing(long mid, long requestId, int payer, int payee, long amount) {
    public String ref() { return WalletGlBridge.ref(mid, requestId); }
  }
}
