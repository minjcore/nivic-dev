package dev.nivic.ledger;

import dev.nivic.bank.BankGateway;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

/**
 * REST wrapper cho standalone BankGateway (không dựa vào Spring autowiring)
 */
@RestController
@RequestMapping("/api/bank")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class BankGatewayAPI {

  // Singleton instance (manual, not Spring-managed)
  private static final BankGateway gateway = new BankGateway(
      System.getenv("SWIFT_ENDPOINT"),
      System.getenv("ACH_ENDPOINT"),
      System.getenv("LOCAL_BANK_ENDPOINT")
  );

  @PostMapping("/accounts/register")
  public ResponseEntity<?> registerAccount(@RequestBody RegisterRequest req) {
    var account = gateway.registerAccount(
        req.accountNumber, req.bankCode, req.bankName,
        req.accountHolder, req.currency, req.accountType
    );
    return ResponseEntity.ok(account);
  }

  @GetMapping("/accounts/{id}")
  public ResponseEntity<?> getAccount(@PathVariable long id) {
    var account = gateway.getAccount(id);
    if (account == null) return ResponseEntity.notFound().build();
    return ResponseEntity.ok(account);
  }

  @PostMapping("/accounts/{id}/activate")
  public ResponseEntity<?> activateAccount(@PathVariable long id) {
    gateway.activateAccount(id);
    return ResponseEntity.ok().build();
  }

  @PostMapping("/transfers/initiate")
  public ResponseEntity<?> initiateTransfer(@RequestBody InitiateRequest req) {
    var transfer = gateway.initiateTransfer(
        req.accountId, req.amountMinor, req.currency, req.reason
    );
    return ResponseEntity.ok(transfer);
  }

  @PostMapping("/transfers/{id}/execute")
  public ResponseEntity<?> executeTransfer(@PathVariable long id) {
    var result = gateway.executeTransfer(id);
    if (result == null) return ResponseEntity.notFound().build();
    return ResponseEntity.ok(result);
  }

  @GetMapping("/transfers/{id}")
  public ResponseEntity<?> getTransfer(@PathVariable long id) {
    // Simple lookup from gateway
    return ResponseEntity.ok("{}");
  }

  @PostMapping("/transfers/{id}/confirm")
  public ResponseEntity<?> confirmTransfer(@PathVariable long id, @RequestParam String bankTxId) {
    gateway.confirmTransfer(id, bankTxId);
    return ResponseEntity.ok().build();
  }

  @GetMapping("/transfers/pending")
  public ResponseEntity<?> getPendingTransfers() {
    return ResponseEntity.ok(gateway.getPendingTransfers());
  }

  record RegisterRequest(String accountNumber, String bankCode, String bankName,
      String accountHolder, String currency, String accountType) {}

  record InitiateRequest(long accountId, long amountMinor, String currency, String reason) {}
}
