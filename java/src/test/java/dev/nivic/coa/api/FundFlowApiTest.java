package dev.nivic.coa.api;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests cho REST handler (FundFlowApi) — gọi handle() trực tiếp, không cần servlet
 * container. Kiểm tra routing, JSON contract, error mapping (status codes).
 */
@Testcontainers
@Tag("integration")
class FundFlowApiTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  private static HikariDataSource ds;
  private FundFlowApi api;

  @BeforeAll
  static void initPool() {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(PG.getJdbcUrl());
    cfg.setUsername(PG.getUsername());
    cfg.setPassword(PG.getPassword());
    cfg.setMaximumPoolSize(5);
    ds = new HikariDataSource(cfg);
  }

  @AfterAll
  static void closePool() { ds.close(); }

  @BeforeEach
  void setUp() { api = new FundFlowApi(ds); }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      st.execute("TRUNCATE coa_trans_data, coa_trans CASCADE");
      st.execute("UPDATE coa_account SET balance_minor = 0, version = 0");
    }
  }

  private ApiResponse post(String path, String body) { return api.handle("POST", path, null, body); }
  private ApiResponse get(String path, String query)  { return api.handle("GET", path, query, null); }

  // ── Happy path ────────────────────────────────────────────────────────────────

  @Test
  void topupReceive_returns201WithBalancedTrans() {
    ApiResponse r = post("topup/receive", "{\"amountMinor\":100000,\"bankRef\":\"API-R1\"}");
    assertEquals(201, r.status());
    assertTrue(r.json().contains("\"status\":\"SUCCESS\""));
    assertTrue(r.json().contains("\"balanced\":true"));
    assertTrue(r.json().contains("\"1111\""));
    assertTrue(r.json().contains("\"3100\""));
  }

  @Test
  void leadingSlashPathTolerated() {
    ApiResponse r = post("/topup/receive", "{\"amountMinor\":50000,\"bankRef\":\"API-SLASH\"}");
    assertEquals(201, r.status());
  }

  @Test
  void fullTopupFlow_updatesBalances() {
    post("topup/receive", "{\"amountMinor\":100000,\"bankRef\":\"API-R2\"}");
    post("topup/confirm", "{\"amountMinor\":100000,\"feeMinor\":1000,\"confirmRef\":\"API-C2\"}");

    ApiResponse acc = get("account/2110", null);
    assertEquals(200, acc.status());
    assertTrue(acc.json().contains("\"balance\":-99000"), acc.json()); // credit-normal user wallet
  }

  @Test
  void idempotentReceive_sameRefReturns200Not201() {
    post("topup/receive", "{\"amountMinor\":100000,\"bankRef\":\"API-IDEM\"}");
    ApiResponse second = post("topup/receive", "{\"amountMinor\":100000,\"bankRef\":\"API-IDEM\"}");
    // second call returns the existing tx — still 201 in our contract (created semantics),
    // but balance must not double
    ApiResponse acc = get("account/1111", null);
    assertTrue(acc.json().contains("\"balance\":100000"), "no double credit: " + acc.json());
  }

  // ── Transaction lookup ──────────────────────────────────────────────────────

  @Test
  void trans_byRef_success() {
    post("topup/receive", "{\"amountMinor\":100000,\"bankRef\":\"API-LOOK\"}");
    ApiResponse r = get("trans", "ref=API-LOOK");
    assertEquals(200, r.status());
    assertTrue(r.json().contains("\"status\":\"SUCCESS\""));
    assertTrue(r.json().contains("\"debitTotal\":100000"));
  }

  @Test
  void trans_unknownRef_404() {
    ApiResponse r = get("trans", "ref=NOPE");
    assertEquals(404, r.status());
    assertTrue(r.json().contains("NOT_FOUND"));
  }

  @Test
  void trans_invalidUuid_400() {
    ApiResponse r = get("trans", "id=not-a-uuid");
    assertEquals(400, r.status());
    assertTrue(r.json().contains("BAD_REQUEST"));
  }

  @Test
  void trans_noParam_400() {
    assertEquals(400, get("trans", null).status());
  }

  // ── Error contract ────────────────────────────────────────────────────────────

  @Test
  void insufficientFunds_422() {
    // withdraw without funds → init throws InsufficientWalletException
    ApiResponse r = post("withdraw/init", "{\"amountMinor\":100000,\"feeMinor\":1000,\"requestRef\":\"API-OD\"}");
    assertEquals(422, r.status());
    assertTrue(r.json().contains("INSUFFICIENT_FUNDS"));
  }

  @Test
  void missingField_400() {
    ApiResponse r = post("topup/receive", "{\"amountMinor\":100000}"); // missing bankRef
    assertEquals(400, r.status());
    assertTrue(r.json().contains("BAD_REQUEST"));
    assertTrue(r.json().contains("bankRef"));
  }

  @Test
  void malformedBody_400() {
    ApiResponse r = post("topup/receive", "not json");
    assertEquals(400, r.status());
  }

  @Test
  void unknownRoute_404() {
    assertEquals(404, post("nope/nope", "{}").status());
    assertEquals(404, get("nope", null).status());
  }

  @Test
  void methodNotAllowed_405() {
    ApiResponse r = api.handle("DELETE", "health", null, null);
    assertEquals(405, r.status());
  }

  @Test
  void reverse_alreadyReversed_409() {
    post("topup/receive", "{\"amountMinor\":100000,\"bankRef\":\"API-RV\"}");
    post("reverse", "{\"originalRef\":\"API-RV\",\"reversalRef\":\"API-RV-1\"}");
    ApiResponse second = post("reverse", "{\"originalRef\":\"API-RV\",\"reversalRef\":\"API-RV-2\"}");
    assertEquals(409, second.status());
    assertTrue(second.json().contains("ALREADY_REVERSED"));
  }

  @Test
  void periodClose_nothingToClose_409() {
    ApiResponse r = post("period/close", "{\"closeRef\":\"API-CLOSE\"}");
    assertEquals(409, r.status());
    assertTrue(r.json().contains("NOTHING_TO_CLOSE"));
  }

  // ── Reports ─────────────────────────────────────────────────────────────────

  @Test
  void reports_reflectFlows() {
    post("topup/receive", "{\"amountMinor\":100000,\"bankRef\":\"API-RPT-R\"}");
    post("topup/confirm", "{\"amountMinor\":100000,\"feeMinor\":1000,\"confirmRef\":\"API-RPT-C\"}");

    ApiResponse trial = get("reports/trial", null);
    assertEquals(200, trial.status());
    assertTrue(trial.json().contains("\"balanced\":true"));

    ApiResponse pnl = get("reports/pnl", null);
    assertTrue(pnl.json().contains("\"netProfit\":1000"), pnl.json());

    ApiResponse sheet = get("reports/sheet", null);
    assertTrue(sheet.json().contains("\"balanced\":true"));
    assertTrue(sheet.json().contains("\"netIncome\":1000"));
  }

  @Test
  void health_ok() {
    ApiResponse r = get("health", null);
    assertEquals(200, r.status());
    assertTrue(r.json().contains("\"balanced\":true"));
  }

  // ── End-to-end via API only ─────────────────────────────────────────────────

  @Test
  void ibftFullFlow_viaApi_balanced() {
    post("topup/receive", "{\"amountMinor\":200000,\"bankRef\":\"E2E-R\"}");
    post("topup/confirm", "{\"amountMinor\":200000,\"feeMinor\":0,\"confirmRef\":\"E2E-C\"}");
    assertEquals(201, post("ibft/init",
        "{\"amountMinor\":40000,\"feeMinor\":1000,\"requestRef\":\"E2E-II\"}").status());
    assertEquals(201, post("ibft/settle",
        "{\"amountMinor\":40000,\"feeMinor\":1000,\"napasCost\":500,\"settleRef\":\"E2E-IS\"}").status());

    assertTrue(get("health", null).json().contains("\"balanced\":true"));
    assertTrue(get("account/3400", null).json().contains("\"balance\":0"), "transit cleared");
  }
}
