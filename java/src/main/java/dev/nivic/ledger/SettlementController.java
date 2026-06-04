package dev.nivic.ledger;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/settlement")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class SettlementController {

  private final SettlementService settlementService;
  private final SettlementManager settlementManager;

  public SettlementController(SettlementService settlementService, SettlementManager settlementManager) {
    this.settlementService = settlementService;
    this.settlementManager = settlementManager;
  }

  // Legacy API (SettlementService)
  @GetMapping("/balance/{currency}")
  public ResponseEntity<?> checkBalance(@PathVariable String currency) {
    return ResponseEntity.ok(settlementService.checkBalance(currency.toUpperCase()));
  }

  @PostMapping("/initiate")
  public ResponseEntity<?> initiateSettlement(@RequestBody SettlementInitiateRequest req) {
    return ResponseEntity.ok(
        settlementService.initiateSettlement(
            req.currency.toUpperCase(),
            req.amountCrypto,
            req.bankAccount
        )
    );
  }

  @GetMapping("/{id}")
  public ResponseEntity<?> getSettlement(@PathVariable long id) {
    var settlement = settlementManager.get(id);
    if (settlement.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(settlement.get());
  }

  @PostMapping("/{id}/execute")
  public ResponseEntity<?> executeSettlement(@PathVariable long id, @RequestParam String bankTransactionId) {
    try {
      settlementManager.execute(id, bankTransactionId);
      return ResponseEntity.ok(settlementManager.get(id).get());
    } catch (SettlementException e) {
      return ResponseEntity.badRequest().body(e.getMessage());
    }
  }

  @PostMapping("/{id}/confirm")
  public ResponseEntity<?> confirmSettlement(@PathVariable long id,
      @RequestParam String bankTransactionId) {
    settlementManager.confirm(id, bankTransactionId);
    return ResponseEntity.ok(settlementManager.get(id).get());
  }

  // New wallet-based settlement API
  @PostMapping("/wallet/initiate")
  public ResponseEntity<?> initiateWalletSettlement(@RequestBody WalletSettlementRequest req) {
    var settlement = settlementManager.initiate(
        req.walletId(), req.amountMinor(), req.type(), req.currency(), req.destination()
    );
    return ResponseEntity.ok(settlement);
  }

  @PostMapping("/wallet/{id}/hold")
  public ResponseEntity<?> holdSettlement(@PathVariable long id) {
    settlementManager.hold(id);
    return ResponseEntity.ok(settlementManager.get(id).get());
  }

  @PostMapping("/wallet/{id}/post")
  public ResponseEntity<?> postSettlement(@PathVariable long id, @RequestParam long transactionId) {
    settlementManager.post(id, transactionId);
    return ResponseEntity.ok(settlementManager.get(id).get());
  }

  @PostMapping("/wallet/{id}/fail")
  public ResponseEntity<?> failSettlement(@PathVariable long id, @RequestParam String reason) {
    settlementManager.fail(id, reason);
    return ResponseEntity.ok(settlementManager.get(id).get());
  }

  @PostMapping("/wallet/{id}/retry")
  public ResponseEntity<?> retrySettlement(@PathVariable long id) {
    settlementManager.retry(id);
    return ResponseEntity.ok(settlementManager.get(id).get());
  }

  @GetMapping("/wallet/{walletId}/list")
  public ResponseEntity<?> listByWallet(@PathVariable long walletId) {
    return ResponseEntity.ok(settlementManager.listByWallet(walletId));
  }

  @GetMapping("/pending")
  public ResponseEntity<?> listPending() {
    return ResponseEntity.ok(settlementManager.listPending());
  }

  @GetMapping("/status/{status}")
  public ResponseEntity<?> listByStatus(@PathVariable String status) {
    return ResponseEntity.ok(settlementManager.listByStatus(status));
  }

  record SettlementInitiateRequest(String currency, long amountCrypto, String bankAccount) {}
  record WalletSettlementRequest(
      long walletId,
      long amountMinor,
      String type,
      String currency,
      String destination
  ) {}
}
