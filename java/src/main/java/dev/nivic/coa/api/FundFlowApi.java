package dev.nivic.coa.api;

import dev.nivic.coa.CoaTrans;
import dev.nivic.coa.CoaTransLine;
import dev.nivic.coa.FundFlowLedger;
import dev.nivic.coa.JdbcFundFlowLedger;
import dev.nivic.coa.cmd.*;
import dev.nivic.coa.error.AlreadyReversedException;
import dev.nivic.coa.error.InsufficientEscrowException;
import dev.nivic.coa.error.InsufficientTransitException;
import dev.nivic.coa.error.InsufficientWalletException;
import dev.nivic.coa.error.NegativeBalanceException;
import dev.nivic.coa.error.NothingToCloseException;
import dev.nivic.coa.error.TransactionNotFoundException;
import dev.nivic.coa.report.BalanceSheet;
import dev.nivic.coa.report.FundFlowReports;
import dev.nivic.coa.report.ProfitAndLoss;
import dev.nivic.coa.report.TrialBalance;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Transport-agnostic REST handler for the GtelPay fund-flow ledger.
 *
 * <p>{@link #handle(String, String, String, String)} maps {@code (method, path, query, body)} to a
 * ledger operation and returns an {@link ApiResponse} (HTTP status + JSON). Domain exceptions are
 * mapped to a stable error contract:</p>
 *
 * <ul>
 *   <li>400 {@code BAD_REQUEST} — malformed body / invalid argument</li>
 *   <li>404 {@code NOT_FOUND} — unknown transaction / account</li>
 *   <li>409 {@code ALREADY_REVERSED} / {@code NOTHING_TO_CLOSE} — conflicting state</li>
 *   <li>422 {@code INSUFFICIENT_FUNDS} — wallet/transit/escrow shortfall</li>
 *   <li>500 {@code INTERNAL} — unexpected</li>
 * </ul>
 *
 * <p>Idempotency is delegated to the ledger via the {@code *Ref} fields (a repeated request with the
 * same ref returns the original transaction, HTTP 200).</p>
 */
public final class FundFlowApi {

  private final FundFlowLedger ledger;
  private final FundFlowReports reports;

  public FundFlowApi(DataSource dataSource) {
    Objects.requireNonNull(dataSource, "dataSource");
    this.ledger = new JdbcFundFlowLedger(dataSource);
    this.reports = new FundFlowReports(dataSource);
    this.ledger.isDoubleEntryBalanced(); // ensure schema/seed
  }

  public FundFlowApi(FundFlowLedger ledger, FundFlowReports reports) {
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.reports = Objects.requireNonNull(reports, "reports");
  }

  /**
   * @param method HTTP method (GET/POST)
   * @param path   path under the API root, e.g. {@code "topup/receive"} (no leading slash needed)
   * @param query  raw query string (may be {@code null})
   * @param body   request body (JSON; may be {@code null}/empty for GET)
   */
  public ApiResponse handle(String method, String path, String query, String body) {
    String p = path == null ? "" : path.replaceAll("^/+", "").replaceAll("/+$", "");
    try {
      if ("GET".equalsIgnoreCase(method)) return get(p, query);
      if ("POST".equalsIgnoreCase(method)) return post(p, MiniJson.parse(body));
      return ApiResponse.error(405, "METHOD_NOT_ALLOWED", method);
    } catch (IllegalArgumentException e) {
      return ApiResponse.error(400, "BAD_REQUEST", e.getMessage());
    } catch (InsufficientWalletException | InsufficientTransitException
        | InsufficientEscrowException e) {
      return ApiResponse.error(422, "INSUFFICIENT_FUNDS", e.getMessage());
    } catch (NegativeBalanceException e) {
      return ApiResponse.error(422, "NEGATIVE_BALANCE", e.getMessage());
    } catch (dev.nivic.coa.error.SegregationOfDutiesException e) {
      return ApiResponse.error(409, "SEGREGATION_OF_DUTIES", e.getMessage());
    } catch (dev.nivic.coa.error.ProposalStateException e) {
      return ApiResponse.error(409, "PROPOSAL_STATE", e.getMessage());
    } catch (dev.nivic.coa.error.ProposalNotFoundException e) {
      return ApiResponse.error(404, "NOT_FOUND", e.getMessage());
    } catch (AlreadyReversedException e) {
      return ApiResponse.error(409, "ALREADY_REVERSED", e.getMessage());
    } catch (NothingToCloseException e) {
      return ApiResponse.error(409, "NOTHING_TO_CLOSE", e.getMessage());
    } catch (dev.nivic.coa.error.NothingToRevalueException e) {
      return ApiResponse.error(409, "NOTHING_TO_REVALUE", e.getMessage());
    } catch (TransactionNotFoundException e) {
      return ApiResponse.error(404, "NOT_FOUND", e.getMessage());
    } catch (RuntimeException e) {
      return ApiResponse.error(500, "INTERNAL", String.valueOf(e.getMessage()));
    }
  }

  // ── POST routes ────────────────────────────────────────────────────────────

  private ApiResponse post(String p, MiniJson b) {
    CoaTrans t = switch (p) {
      case "topup/receive" -> ledger.receiveTopUp(
          new TopUpReceiveCmd(b.reqLong("amountMinor"), b.reqString("bankRef"), b.optString("memo")));
      case "topup/confirm" -> ledger.confirmTopUp(
          new TopUpConfirmCmd(b.reqLong("amountMinor"), b.reqLong("feeMinor"),
              b.reqString("confirmRef"), b.optString("memo")));

      case "withdraw/init" -> ledger.initWithdraw(
          new WithdrawInitCmd(b.reqLong("amountMinor"), b.reqLong("feeMinor"),
              b.reqString("requestRef"), b.optString("memo")));
      case "withdraw/settle" -> ledger.settleWithdraw(
          new WithdrawSettleCmd(b.reqLong("amountMinor"), b.reqLong("feeMinor"),
              b.reqString("settleRef"), b.optString("memo")));

      case "transfer/init" -> ledger.initInternalTransfer(
          new InternalTransferInitCmd(b.reqLong("amountMinor"), b.reqLong("feeMinor"),
              b.reqString("requestRef"), b.optString("memo")));
      case "transfer/settle" -> ledger.settleInternalTransfer(
          new InternalTransferSettleCmd(b.reqLong("amountMinor"), b.reqLong("feeMinor"),
              b.reqString("settleRef"), b.optString("memo")));

      case "ibft/init" -> ledger.initIbftTransfer(
          new IbftInitCmd(b.reqLong("amountMinor"), b.reqLong("feeMinor"),
              b.reqString("requestRef"), b.optString("memo")));
      case "ibft/settle" -> ledger.settleIbftTransfer(
          new IbftSettleCmd(b.reqLong("amountMinor"), b.reqLong("feeMinor"), b.reqLong("napasCost"),
              b.reqString("settleRef"), b.optString("memo")));

      case "qrpos/receive" -> ledger.receiveQrPos(
          new QrPosReceiveCmd(b.reqLong("amountMinor"), b.reqLong("vpbankCost"),
              b.reqString("requestRef"), b.optString("memo")));
      case "qrpos/credit" -> ledger.creditMerchantQrPos(
          new QrPosCreditMerchantCmd(b.reqLong("amountMinor"), b.reqString("settleRef"), b.optString("memo")));

      case "wallet-payment/init" -> ledger.initWalletPayment(
          new WalletPaymentInitCmd(b.reqLong("amountMinor"), b.reqString("requestRef"), b.optString("memo")));
      case "wallet-payment/settle" -> ledger.settleWalletPayment(
          new WalletPaymentSettleCmd(b.reqLong("amountMinor"), b.reqString("settleRef"), b.optString("memo")));

      case "payroll/init" -> ledger.initPayroll(
          new PayrollInitCmd(b.reqLong("amount"), b.reqLong("totalFee"), b.reqInt("employeeCount"),
              b.reqString("requestRef"), b.optString("memo")));
      case "payroll/disburse" -> ledger.disbursePayroll(
          new PayrollDisburseCmd(b.reqLong("amount"), b.reqLong("totalFee"), b.reqLong("napasCost"),
              b.reqString("disburseRef"), b.optString("memo")));

      case "disbursement/prefund" -> ledger.prefundDisbursement(
          new DisbursementPrefundCmd(b.reqLong("amount"), b.reqString("prefundRef"), b.optString("memo")));
      case "disbursement/init" -> ledger.initDisbursement(
          new DisbursementInitCmd(b.reqLong("amount"), b.reqLong("fee"),
              b.reqString("requestRef"), b.optString("memo")));
      case "disbursement/settle" -> ledger.settleDisbursement(
          new DisbursementSettleCmd(b.reqLong("amount"), b.reqLong("fee"), b.reqLong("napasCost"),
              b.reqString("settleRef"), b.optString("memo")));

      case "eod/clearing" -> ledger.eodInitClearing(
          new EodClearingInitCmd(b.reqLong("totalAmount"), b.reqString("clearingRef"), b.optString("memo")));
      case "eod/reconcile" -> ledger.eodReconcile(
          new EodReconcileCmd(b.reqLong("totalAmount"), b.reqLong("mdrAmount"),
              b.reqString("reconcileRef"), b.optString("memo")));
      case "eod/recognize-mdr" -> ledger.eodRecognizeMdr(
          new EodRecognizeMdrCmd(b.reqLong("mdrAmount"), b.reqString("mdrRef"), b.optString("memo")));
      case "eod/settle-outbound" -> ledger.eodSettleOutbound(
          new EodSettleOutboundCmd(b.reqLong("netAmount"), b.reqLong("napasCost"),
              b.reqString("settleRef"), b.optString("memo")));
      case "eod/reject" -> ledger.eodRejectSettlement(
          new EodRejectSettlementCmd(b.reqLong("netAmount"), b.reqLong("mdrAmount"),
              b.reqString("rejectRef"), b.optString("memo")));

      case "reverse" -> ledger.reverse(
          new ReversalCmd(b.reqString("originalRef"), b.reqString("reversalRef"), b.optString("memo")));
      case "period/close" -> ledger.closePeriod(
          new PeriodCloseCmd(b.reqString("closeRef"), b.optString("memo")));

      case "fx/exchange" -> ledger.fxExchange(
          new FxExchangeCmd(b.reqLong("vndAmount"), b.reqLong("usdAmount"),
              b.has("buyUsd") && Boolean.parseBoolean(b.reqString("buyUsd")),
              b.reqString("requestRef"), b.optString("memo")));
      case "fx/revalue" -> ledger.fxRevalue(
          new FxRevalueCmd(b.reqLong("rateVndPerUsd"), b.reqString("requestRef"), b.optString("memo")));

      default -> null;
    };
    if (t == null) return ApiResponse.error(404, "NOT_FOUND", "unknown route: POST " + p);
    return ApiResponse.created(transJson(t));
  }

  // ── GET routes ─────────────────────────────────────────────────────────────

  private ApiResponse get(String p, String query) {
    if ("health".equals(p)) {
      return ApiResponse.ok("{\"balanced\":" + ledger.isDoubleEntryBalanced() + "}");
    }
    if (p.startsWith("account/")) {
      String code = p.substring("account/".length());
      long bal = ledger.getBalance(code);
      return ApiResponse.ok("{\"code\":" + MiniJson.str(code) + ",\"balance\":" + bal + "}");
    }
    if ("trans".equals(p)) {
      String id = param(query, "id");
      String ref = param(query, "ref");
      CoaTrans t = null;
      if (id != null && !id.isBlank()) t = ledger.findTrans(UUID.fromString(id.trim()));
      else if (ref != null && !ref.isBlank()) t = ledger.findTransByRefId(ref.trim());
      else return ApiResponse.error(400, "BAD_REQUEST", "require ?id= or ?ref=");
      if (t == null) return ApiResponse.error(404, "NOT_FOUND", "transaction not found");
      return ApiResponse.ok(transJson(t));
    }
    if ("reports/trial".equals(p)) return ApiResponse.ok(trialJson(reports.trialBalance()));
    if ("reports/sheet".equals(p)) return ApiResponse.ok(sheetJson(reports.balanceSheet()));
    if ("reports/pnl".equals(p))   return ApiResponse.ok(pnlJson(reports.profitAndLoss()));
    if ("reports/cashflow".equals(p)) return ApiResponse.ok(cashFlowJson(reports.cashFlow()));
    if ("reports/cashflow-statement".equals(p)) {
      var cf = reports.cashFlowStatement();
      return ApiResponse.ok("{\"operating\":" + cf.operating()
          + ",\"investing\":" + cf.investing()
          + ",\"financing\":" + cf.financing()
          + ",\"netCashFlow\":" + cf.netCashFlow()
          + ",\"openingCash\":" + cf.openingCash()
          + ",\"closingCash\":" + cf.closingCash()
          + ",\"consistent\":" + cf.isConsistent() + "}");
    }
    return ApiResponse.error(404, "NOT_FOUND", "unknown route: GET " + p);
  }

  // ── JSON serializers ─────────────────────────────────────────────────────────

  static String transJson(CoaTrans t) {
    StringBuilder sb = new StringBuilder();
    boolean ok = t.isBalanced() && t.lines().size() >= 2;
    sb.append('{')
      .append("\"id\":").append(MiniJson.str(t.id().toString())).append(',')
      .append("\"ref\":").append(MiniJson.str(t.refId())).append(',')
      .append("\"memo\":").append(MiniJson.str(t.memo())).append(',')
      .append("\"createdAt\":").append(MiniJson.str(String.valueOf(t.createdAt()))).append(',')
      .append("\"debitTotal\":").append(t.debitTotal()).append(',')
      .append("\"creditTotal\":").append(t.creditTotal()).append(',')
      .append("\"balanced\":").append(t.isBalanced()).append(',')
      .append("\"status\":").append(MiniJson.str(ok ? "SUCCESS" : "INVALID")).append(',')
      .append("\"lines\":[");
    boolean first = true;
    for (CoaTransLine l : t.lines()) {
      if (!first) sb.append(',');
      first = false;
      sb.append("{\"lineNo\":").append(l.lineNo())
        .append(",\"account\":").append(MiniJson.str(l.accountCode()))
        .append(",\"name\":").append(MiniJson.str(l.accountName()))
        .append(",\"debit\":").append(l.debitMinor())
        .append(",\"credit\":").append(l.creditMinor())
        .append('}');
    }
    return sb.append("]}").toString();
  }

  private static String trialJson(TrialBalance tb) {
    StringBuilder sb = new StringBuilder("{\"totalDebit\":").append(tb.totalDebit())
        .append(",\"totalCredit\":").append(tb.totalCredit())
        .append(",\"balanced\":").append(tb.isBalanced())
        .append(",\"rows\":[");
    boolean first = true;
    for (var r : tb.rows()) {
      if (!first) sb.append(',');
      first = false;
      sb.append("{\"code\":").append(MiniJson.str(r.code()))
        .append(",\"name\":").append(MiniJson.str(r.name()))
        .append(",\"kind\":").append(MiniJson.str(r.kind()))
        .append(",\"debit\":").append(r.debitMinor())
        .append(",\"credit\":").append(r.creditMinor())
        .append('}');
    }
    return sb.append("]}").toString();
  }

  private static String sheetJson(BalanceSheet bs) {
    return "{\"assets\":" + bs.assets()
        + ",\"liabilities\":" + bs.liabilities()
        + ",\"equity\":" + bs.equity()
        + ",\"netIncome\":" + bs.netIncome()
        + ",\"transit\":" + bs.transit()
        + ",\"balanced\":" + bs.isBalanced() + "}";
  }

  private static String cashFlowJson(dev.nivic.coa.report.CashFlow cf) {
    StringBuilder sb = new StringBuilder("{\"openingCash\":").append(cf.openingCash())
        .append(",\"inflows\":").append(cf.inflows())
        .append(",\"outflows\":").append(cf.outflows())
        .append(",\"netCashFlow\":").append(cf.netCashFlow())
        .append(",\"closingCash\":").append(cf.closingCash())
        .append(",\"consistent\":").append(cf.isConsistent())
        .append(",\"byAccount\":[");
    boolean first = true;
    for (var l : cf.byAccount()) {
      if (!first) sb.append(',');
      first = false;
      sb.append("{\"code\":").append(MiniJson.str(l.code()))
        .append(",\"name\":").append(MiniJson.str(l.name()))
        .append(",\"inflow\":").append(l.inflow())
        .append(",\"outflow\":").append(l.outflow())
        .append(",\"net\":").append(l.net())
        .append('}');
    }
    return sb.append("]}").toString();
  }

  private static String pnlJson(ProfitAndLoss pl) {
    StringBuilder sb = new StringBuilder("{\"totalRevenue\":").append(pl.totalRevenue())
        .append(",\"totalExpense\":").append(pl.totalExpense())
        .append(",\"netProfit\":").append(pl.netProfit())
        .append(",\"revenue\":").append(linesJson(pl.revenue()))
        .append(",\"expense\":").append(linesJson(pl.expense()))
        .append('}');
    return sb.toString();
  }

  private static String linesJson(java.util.List<ProfitAndLoss.Line> lines) {
    StringBuilder sb = new StringBuilder("[");
    boolean first = true;
    for (var l : lines) {
      if (!first) sb.append(',');
      first = false;
      sb.append("{\"code\":").append(MiniJson.str(l.code()))
        .append(",\"name\":").append(MiniJson.str(l.name()))
        .append(",\"amount\":").append(l.amount())
        .append('}');
    }
    return sb.append("]").toString();
  }

  private static String param(String query, String key) {
    if (query == null) return null;
    for (String kv : query.split("&")) {
      int eq = kv.indexOf('=');
      if (eq > 0 && kv.substring(0, eq).equals(key)) {
        return java.net.URLDecoder.decode(kv.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
      }
    }
    return null;
  }
}
