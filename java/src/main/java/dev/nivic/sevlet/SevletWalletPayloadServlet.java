package dev.nivic.sevlet;

import dev.nivic.db.PostgresContextListener;
import dev.nivic.journal.JdbcWalletJournal;
import dev.nivic.journal.MemoryWalletJournal;
import dev.nivic.journal.NoopWalletJournal;
import dev.nivic.journal.WalletJournal;
import dev.nivic.ledger.JdbcPaymentLedger;
import dev.nivic.ledger.JdbcWalletLedger;
import dev.nivic.ledger.MemoryPaymentLedger;
import dev.nivic.ledger.MemoryWalletLedger;
import dev.nivic.ledger.NoopPaymentLedger;
import dev.nivic.ledger.NoopWalletLedger;
import dev.nivic.ledger.OrderIdConflictException;
import dev.nivic.ledger.PaymentLedger;
import dev.nivic.ledger.WalletLedger;
import dev.nivic.payment.AcceptResult;
import dev.nivic.payment.AccountHoldStore;
import dev.nivic.payment.JdbcAccountHoldStore;
import dev.nivic.payment.JdbcPaymentIntentExpiry;
import dev.nivic.payment.LedgerService;
import dev.nivic.payment.NoopAccountHoldStore;
import dev.nivic.payment.PaymentIntentExpiryScheduler;
import dev.nivic.payment.ReconciliationJob;
import dev.nivic.payment.WalletAcceptService;
import dev.nivic.payment.WalService;
import dev.nivic.payment.WalletPayloadJson;
import dev.nivic.payment.WalletVerificationService;
import dev.nivic.payment.disruptor.WalletPersistDisruptor;
import dev.nivic.wal.CoreWalSigner;
import dev.nivic.wal.Ed25519WalKeys;
import dev.nivic.sevlet.idempotency.IdempotencyGate;
import dev.nivic.sevlet.idempotency.JdbcIdempotencyGate;
import dev.nivic.sevlet.idempotency.MemoryIdempotencyGate;
import dev.nivic.sevlet.secret.JdbcMidSecretResolver;
import dev.nivic.sevlet.secret.MidSecretResolver;
import dev.nivic.sevlet.secret.UnknownMidException;
import dev.nivic.sevlet.idempotency.OrderIdMismatchException;
import dev.nivic.merchant.MerchantDisabledException;
import dev.nivic.wal.SimpleWalLog;
import jakarta.servlet.ServletContext;
import dev.nivic.config.ApplicationProperties;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Currency;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/**
 * POST <strong>raw binary</strong> body ({@link SevletWalletCodec} layout). The request is
 * <strong>not</strong> {@code application/json}; clients should send e.g. {@code
 * application/octet-stream} or omit {@code Content-Type}. Response is JSON for debugging /
 * inspection only.
 *
 * <p>HMAC-SHA256 on {@code mid}: loads {@code secret_key} from {@code wallet_mid_secret} when
 * {@code midSecretMode} is {@code jdbc} or {@code auto} with a DataSource. Last 32 bytes are
 * {@code sig}; MAC input is {@code raw[OFFSET_COMMAND .. len-32)} (from {@code command} through
 * {@code extraData}; 3-byte pad only excluded). Unknown {@code mid} → 403; bad MAC → 401. {@code
 * midSecretMode=skip} disables verification.</p>
 *
 * <p>Optional {@code merchant_config} (same {@code mid}): {@code enabled}, {@code intent_ttl_minutes},
 * {@code display_name}. Loaded with the HMAC row via {@link JdbcMidSecretResolver}; when {@code
 * enabled} is false, requests are rejected with 403 after a valid MAC.</p>
 *
 * <p>{@code extraData} is inside the MAC’d region (after fixed numeric tail).</p>
 *
 * <p>Idempotency on {@code (mid, requestId)} (see {@code wallet_idempotency}; {@code order_id}).
 * When {@code wallet_mid_secret.payment_check_order} is true for that {@code mid}, the {@code mid}
 * is in <strong>order payment</strong> mode: duplicate retries must reuse the same {@code orderId}
 * ( {@link OrderIdMismatchException} ), WAL plus initial {@code payment_ledger} row (no
 * {@code debit}/{@code credit}). {@code wallet_ledger}/journal stay deferred until a settle/replay
 * path. When false, idempotency ignores {@code orderId} on duplicates; after WAL,
 * {@code wallet_ledger}+journal run first, then {@code payment_ledger} is upserted in a separate
 * JDBC transaction (settled row with accounts).</p>
 *
 * <p>Init parameter {@code maxBodyBytes} (default 1048576). Init parameter {@code
 * maxExtraDataBytes} (default {@link ExtraDataPolicy#DEFAULT_MAX_EXTRA_DATA_BYTES}) caps {@code
 * extraData} after decode.</p>
 *
 * <p>Append-only WAL of raw POST bodies: init {@code walletWalPath} (default {@code
 * ${java.io.tmpdir}/sevlet-wallet.wal}), optional {@code walletWalSync=true} for {@code DSYNC} per
 * {@link SimpleWalLog}. Records are written only after HMAC verification succeeds (or {@code
 * midSecretMode=skip}) and the idempotency gate accepts {@code (mid, requestId)}.</p>
 *
 * <p>Optional LMAX Disruptor: init {@code walletDisruptorRingSize} (power-of-2 ring, default {@code
 * 1024}). Set {@code 0} to persist WAL + ledger on the servlet thread (no ring).</p>
 *
 * <p>Optional Core-signed WAL: init {@code walletWalSigningKeyPath} or env {@code
 * NIVIC_WAL_SIGNING_KEY_PATH} (PKCS#8 DER Ed25519 private key), or {@code NIVIC_WAL_SIGNING_KEY_B64}.
 * When set, each WAL record is an NVW2 frame (see {@link dev.nivic.wal.SignedWalVerifier}). CLI: {@code
 * mvn exec:java -Dexec.args="path/to/wal pub.der"}.</p>
 *
 * <p>Order intents: init {@code paymentIntentTtlMinutes} (default {@code 15}). TTL expiry job runs
 * every minute when JDBC {@code payment_ledger} is active.</p>
 *
 * <p>Ledger row in {@code wallet_ledger} (or in-memory / noop): init {@code ledgerStorage} = {@code
 * auto} (default), {@code jdbc}, {@code memory}, or {@code skip}. {@code auto} uses JDBC when a
 * {@link javax.sql.DataSource} is on the context, else in-memory. Init {@code ledgerCurrency}
 * (ISO 4217, default {@code USD}) labels how wire {@code amount} maps to minor units. Non-order
 * mids: append runs after WAL, then {@code payment_ledger} ( {@code paymentLedgerStorage} ). Order
 * mids: only {@code payment_ledger} initial row after WAL until settle.</p>
 *
 * <p>Journal (double-entry): tables {@code wallet_journal_entry} + {@code wallet_journal_line}, init
 * {@code journalStorage} = {@code auto} (default), {@code jdbc}, {@code memory}, or {@code skip}.
 * {@code auto} matches ledger behavior. Each payload becomes one entry and two lines: debit
 * {@code debit} / credit {@code credit} accounts with {@code amount} as minor units
 * ({@code ledgerCurrency}). Append runs after ledger.</p>
 *
 * <p>Wire {@code command} (authenticated, first field in MAC) selects an {@link
 * dev.nivic.command.WalletInputCommand} via
 * {@link dev.nivic.command.WalletInputCommands#from(SevletWalletPayload)}; debug JSON includes {@code
 * inputCommand}.</p>
 *
 * <p>Orchestration: {@link dev.nivic.payment.WalletVerificationService}, {@link
 * dev.nivic.payment.WalletAcceptService} ( {@link dev.nivic.payment.WalService}, {@link
 * dev.nivic.payment.LedgerService}, optional {@link dev.nivic.payment.disruptor.WalletPersistDisruptor}
 * ), and optional domain {@link dev.nivic.payment.WalletService} for balance math.</p>
 */
@WebServlet(
    name = "SevletWalletPayloadServlet",
    urlPatterns = "/sevlet/wallet/payload")
public class SevletWalletPayloadServlet extends HttpServlet {

  private static final int DEFAULT_MAX_BODY = 1_048_576;

  private int maxBodyBytes = DEFAULT_MAX_BODY;

  private int maxExtraDataBytes = ExtraDataPolicy.DEFAULT_MAX_EXTRA_DATA_BYTES;

  private WalletVerificationService verification;

  private WalletAcceptService accept;

  private PaymentIntentExpiryScheduler intentExpiryScheduler;

  @Override
  public void init() throws ServletException {
    super.init();
    ServletContext ctx = getServletContext();

    MidSecretResolver midSecrets;
    String msm = getServletConfig().getInitParameter("midSecretMode");
    if (msm == null || msm.isBlank() || "auto".equalsIgnoreCase(msm.trim())) {
      try {
        midSecrets = new JdbcMidSecretResolver(PostgresContextListener.getDataSource(ctx));
        ctx.log("Mid secret: JDBC (wallet_mid_secret)");
      } catch (IllegalStateException e) {
        midSecrets = null;
        ctx.log("WARNING: mid / HMAC verification skipped (no DataSource)");
      }
    } else if ("jdbc".equalsIgnoreCase(msm.trim())) {
      midSecrets = new JdbcMidSecretResolver(PostgresContextListener.getDataSource(ctx));
      ctx.log("Mid secret: JDBC (forced)");
    } else if ("skip".equalsIgnoreCase(msm.trim())) {
      midSecrets = null;
      ctx.log("Mid secret: skipped");
    } else {
      throw new ServletException("invalid midSecretMode: " + msm);
    }
    verification = new WalletVerificationService(midSecrets);

    IdempotencyGate idempotency;
    String mode = getServletConfig().getInitParameter("idempotencyStorage");
    if (mode == null || mode.isBlank() || "auto".equalsIgnoreCase(mode.trim())) {
      try {
        idempotency = new JdbcIdempotencyGate(PostgresContextListener.getDataSource(ctx));
        ctx.log("Idempotency: JDBC (wallet_idempotency)");
      } catch (IllegalStateException e) {
        idempotency = new MemoryIdempotencyGate();
        ctx.log("Idempotency: in-memory (no DataSource on context)");
      }
    } else if ("jdbc".equalsIgnoreCase(mode.trim())) {
      idempotency = new JdbcIdempotencyGate(PostgresContextListener.getDataSource(ctx));
      ctx.log("Idempotency: JDBC (forced)");
    } else if ("memory".equalsIgnoreCase(mode.trim())) {
      idempotency = new MemoryIdempotencyGate();
      ctx.log("Idempotency: in-memory (forced)");
    } else {
      throw new ServletException("invalid idempotencyStorage: " + mode);
    }

    String p = getServletConfig().getInitParameter("maxBodyBytes");
    if (p != null && !p.isBlank()) {
      try {
        maxBodyBytes = Integer.parseInt(p.trim());
      } catch (NumberFormatException e) {
        throw new ServletException("invalid maxBodyBytes: " + p, e);
      }
      if (maxBodyBytes < 0) {
        throw new ServletException("maxBodyBytes must be >= 0");
      }
    }

    String maxExtra = getServletConfig().getInitParameter("maxExtraDataBytes");
    if (maxExtra != null && !maxExtra.isBlank()) {
      try {
        maxExtraDataBytes = Integer.parseInt(maxExtra.trim());
      } catch (NumberFormatException e) {
        throw new ServletException("invalid maxExtraDataBytes: " + maxExtra, e);
      }
      if (maxExtraDataBytes < 0) {
        throw new ServletException("maxExtraDataBytes must be >= 0");
      }
    }

    Currency ledgerCurrency;
    String curParam = getServletConfig().getInitParameter("ledgerCurrency");
    String curCode =
        curParam == null || curParam.isBlank() ? "USD" : curParam.trim().toUpperCase(Locale.ROOT);
    try {
      ledgerCurrency = Currency.getInstance(curCode);
    } catch (IllegalArgumentException e) {
      throw new ServletException("invalid ledgerCurrency: " + curCode, e);
    }

    WalletLedger ledger;
    String lmode = getServletConfig().getInitParameter("ledgerStorage");
    if (lmode == null || lmode.isBlank() || "auto".equalsIgnoreCase(lmode.trim())) {
      try {
        ledger = new JdbcWalletLedger(PostgresContextListener.getDataSource(ctx));
        ctx.log("Ledger: JDBC (wallet_ledger)");
      } catch (IllegalStateException e) {
        ledger = new MemoryWalletLedger();
        ctx.log("Ledger: in-memory (no DataSource on context)");
      }
    } else if ("jdbc".equalsIgnoreCase(lmode.trim())) {
      ledger = new JdbcWalletLedger(PostgresContextListener.getDataSource(ctx));
      ctx.log("Ledger: JDBC (forced)");
    } else if ("memory".equalsIgnoreCase(lmode.trim())) {
      ledger = new MemoryWalletLedger();
      ctx.log("Ledger: in-memory (forced)");
    } else if ("skip".equalsIgnoreCase(lmode.trim())) {
      ledger = new NoopWalletLedger();
      ctx.log("Ledger: skipped");
    } else {
      throw new ServletException("invalid ledgerStorage: " + lmode);
    }

    WalletJournal journal;
    String jmode = getServletConfig().getInitParameter("journalStorage");
    if (jmode == null || jmode.isBlank() || "auto".equalsIgnoreCase(jmode.trim())) {
      try {
        journal = new JdbcWalletJournal(PostgresContextListener.getDataSource(ctx));
        ctx.log("Journal: JDBC (wallet_journal_*)");
      } catch (IllegalStateException e) {
        journal = new MemoryWalletJournal();
        ctx.log("Journal: in-memory (no DataSource on context)");
      }
    } else if ("jdbc".equalsIgnoreCase(jmode.trim())) {
      journal = new JdbcWalletJournal(PostgresContextListener.getDataSource(ctx));
      ctx.log("Journal: JDBC (forced)");
    } else if ("memory".equalsIgnoreCase(jmode.trim())) {
      journal = new MemoryWalletJournal();
      ctx.log("Journal: in-memory (forced)");
    } else if ("skip".equalsIgnoreCase(jmode.trim())) {
      journal = new NoopWalletJournal();
      ctx.log("Journal: skipped");
    } else {
      throw new ServletException("invalid journalStorage: " + jmode);
    }

    AccountHoldStore accountHolds = NoopAccountHoldStore.INSTANCE;
    try {
      DataSource holdDs = PostgresContextListener.getDataSource(ctx);
      accountHolds = new JdbcAccountHoldStore(holdDs);
      ctx.log("Account holds: JDBC (wallet_account_hold)");
    } catch (IllegalStateException e) {
      ctx.log("Account holds: noop (no DataSource on context)");
    }

    PaymentLedger paymentLedger;
    String plMode = getServletConfig().getInitParameter("paymentLedgerStorage");
    if (plMode == null || plMode.isBlank() || "auto".equalsIgnoreCase(plMode.trim())) {
      try {
        paymentLedger =
            new JdbcPaymentLedger(PostgresContextListener.getDataSource(ctx), accountHolds);
        ctx.log("Payment ledger: JDBC (payment_ledger)");
      } catch (IllegalStateException e) {
        paymentLedger = new MemoryPaymentLedger();
        ctx.log("Payment ledger: in-memory (no DataSource on context)");
      }
    } else if ("jdbc".equalsIgnoreCase(plMode.trim())) {
      paymentLedger =
          new JdbcPaymentLedger(PostgresContextListener.getDataSource(ctx), accountHolds);
      ctx.log("Payment ledger: JDBC (forced)");
    } else if ("memory".equalsIgnoreCase(plMode.trim())) {
      paymentLedger = new MemoryPaymentLedger();
      ctx.log("Payment ledger: in-memory (forced)");
    } else if ("skip".equalsIgnoreCase(plMode.trim())) {
      paymentLedger = new NoopPaymentLedger();
      ctx.log("Payment ledger: skipped");
    } else {
      throw new ServletException("invalid paymentLedgerStorage: " + plMode);
    }

    String walPathParam = getServletConfig().getInitParameter("walletWalPath");
    Path walPath =
        Paths.get(
            walPathParam == null || walPathParam.isBlank()
                ? System.getProperty("java.io.tmpdir") + java.io.File.separator + "sevlet-wallet.wal"
                : walPathParam.trim());
    String wsync = getServletConfig().getInitParameter("walletWalSync");
    boolean walSync = wsync != null && "true".equalsIgnoreCase(wsync.trim());
    SimpleWalLog walletWal;
    try {
      walletWal = new SimpleWalLog(walPath, walSync);
    } catch (IOException e) {
      throw new ServletException("wallet WAL open failed: " + walPath.toAbsolutePath(), e);
    }
    ctx.log(
        "Wallet WAL: "
            + walPath.toAbsolutePath()
            + (walSync ? " (DSYNC)" : ""));

    CoreWalSigner walSigner = null;
    String skPath =
        firstNonBlank(
            ApplicationProperties.resolve("NIVIC_WAL_SIGNING_KEY_PATH"),
            getServletConfig().getInitParameter("walletWalSigningKeyPath"));
    String skB64 = ApplicationProperties.resolve("NIVIC_WAL_SIGNING_KEY_B64");
    try {
      if (skPath != null && !skPath.isBlank()) {
        Path skp = Paths.get(skPath.trim());
        if (Files.isRegularFile(skp)) {
          walSigner = new CoreWalSigner(Ed25519WalKeys.loadPrivateKeyDer(skp));
          ctx.log("WAL Core signing: Ed25519 private key " + skp.toAbsolutePath());
        }
      }
      if (walSigner == null && skB64 != null && !skB64.isBlank()) {
        walSigner = new CoreWalSigner(Ed25519WalKeys.loadPrivateKeyFromBase64(skB64));
        ctx.log("WAL Core signing: Ed25519 from NIVIC_WAL_SIGNING_KEY_B64");
      }
    } catch (Exception e) {
      throw new ServletException("WAL signing key load failed", e);
    }

    WalService wal = new WalService(walletWal, walSigner);
    LedgerService ledgerSvc = new LedgerService(ledger, journal);

    int intentTtlMinutes = 15;
    String ttlP = getServletConfig().getInitParameter("paymentIntentTtlMinutes");
    if (ttlP != null && !ttlP.isBlank()) {
      try {
        intentTtlMinutes = Integer.parseInt(ttlP.trim());
      } catch (NumberFormatException e) {
        throw new ServletException("invalid paymentIntentTtlMinutes: " + ttlP, e);
      }
    }

    int ringSize = 1024;
    String dr = getServletConfig().getInitParameter("walletDisruptorRingSize");
    if (dr != null && !dr.isBlank()) {
      try {
        ringSize = Integer.parseInt(dr.trim());
      } catch (NumberFormatException e) {
        throw new ServletException("invalid walletDisruptorRingSize: " + dr, e);
      }
    }
    WalletPersistDisruptor persistRing = null;
    if (ringSize > 0) {
      persistRing =
          new WalletPersistDisruptor(wal, ledgerSvc, paymentLedger, ledgerCurrency, ringSize);
      ctx.log(
          "Wallet persist: LMAX Disruptor ringSize="
              + WalletPersistDisruptor.normalizeRingSize(ringSize));
    } else {
      ctx.log("Wallet persist: synchronous (walletDisruptorRingSize=0)");
    }

    accept =
        new WalletAcceptService(
            idempotency,
            wal,
            ledgerSvc,
            paymentLedger,
            ledgerCurrency,
            midSecrets,
            persistRing,
            intentTtlMinutes);

    intentExpiryScheduler = null;
    try {
      DataSource dsRun = PostgresContextListener.getDataSource(ctx);
      if (paymentLedger instanceof JdbcPaymentLedger) {
        final WalService walRef = wal;
        final AccountHoldStore holdsRef = accountHolds;
        intentExpiryScheduler =
            new PaymentIntentExpiryScheduler(
                () -> {
                  try {
                    int n = JdbcPaymentIntentExpiry.runOnce(dsRun, walRef, holdsRef);
                    if (n > 0) {
                      ctx.log("payment intent TTL: expired " + n + " row(s)");
                    }
                  } catch (Exception ex) {
                    ctx.log("JdbcPaymentIntentExpiry failed", ex);
                  }
                },
                1,
                1,
                TimeUnit.MINUTES);
        ctx.log("Payment intent TTL scheduler: every 1 minute");
      }
    } catch (IllegalStateException e) {
      ctx.log("Payment intent TTL scheduler: disabled (no JDBC DataSource)");
    }

    try {
      DataSource dsRep = PostgresContextListener.getDataSource(ctx);
      ctx.log("Reconciliation snapshot: " + ReconciliationJob.runVerificationReport(dsRep));
    } catch (Exception e) {
      ctx.log("Reconciliation snapshot skipped", e);
    }
  }

  private static String firstNonBlank(String... parts) {
    if (parts == null) {
      return null;
    }
    for (String p : parts) {
      if (p != null && !p.isBlank()) {
        return p.trim();
      }
    }
    return null;
  }

  @Override
  public void destroy() {
    if (intentExpiryScheduler != null) {
      intentExpiryScheduler.close();
      intentExpiryScheduler = null;
    }
    if (accept != null) {
      try {
        accept.close();
      } catch (IOException e) {
        getServletContext().log("Wallet WAL close failed", e);
      }
      accept = null;
    }
    super.destroy();
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
    try {
      if (isApplicationJsonContentType(req.getContentType())) {
        resp.sendError(
            HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
            "Body must be raw SevletWalletCodec binary (e.g. application/octet-stream), not JSON");
        return;
      }
      byte[] raw =
          BinaryBodyReader.readFully(
              req.getInputStream(), req.getContentLengthLong(), maxBodyBytes);
      SevletWalletPayload payload = SevletWalletCodec.decode(raw);
      ExtraDataPolicy.validateLength(payload.extraData(), maxExtraDataBytes);
      verification.verify(raw, payload);
      AcceptResult ar = accept.claimAndPersist(raw, payload);
      if (ar.isDuplicate()) {
        resp.sendError(
            HttpServletResponse.SC_CONFLICT,
            "duplicate mid/requestId (idempotency): mid="
                + Long.toUnsignedString(payload.mid())
                + " requestId="
                + Long.toUnsignedString(payload.requestId()));
        return;
      }
      resp.setStatus(HttpServletResponse.SC_OK);
      resp.setContentType("application/json");
      String json =
          ar.intentAck()
              .map(ack -> WalletPayloadJson.formatWithIntentAck(payload, ack))
              .orElseGet(() -> WalletPayloadJson.format(payload));
      resp.getWriter().write(json);
    } catch (BodyTooLargeException e) {
      resp.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, e.getMessage());
    } catch (UnknownMidException e) {
      resp.sendError(HttpServletResponse.SC_FORBIDDEN, e.getMessage());
    } catch (MerchantDisabledException e) {
      resp.sendError(HttpServletResponse.SC_FORBIDDEN, e.getMessage());
    } catch (OrderIdMismatchException e) {
      resp.sendError(HttpServletResponse.SC_CONFLICT, e.getMessage());
    } catch (OrderIdConflictException e) {
      resp.sendError(HttpServletResponse.SC_CONFLICT, e.getMessage());
    } catch (SecurityException e) {
      resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
    } catch (IllegalArgumentException e) {
      resp.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (IllegalStateException e) {
      resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  /** True when clients declare JSON; this servlet expects binary wire format, not JSON. */
  static boolean isApplicationJsonContentType(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return false;
    }
    String base = contentType.trim();
    int semi = base.indexOf(';');
    if (semi >= 0) {
      base = base.substring(0, semi).trim();
    }
    return base.toLowerCase(Locale.ROOT).equals("application/json");
  }
}
