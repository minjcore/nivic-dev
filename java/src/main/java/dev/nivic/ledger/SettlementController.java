package dev.nivic.ledger;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settlement")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class SettlementController {

  private final SettlementService settlementService;

  public SettlementController(SettlementService settlementService) {
    this.settlementService = settlementService;
  }

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
    var settlement = settlementService.getSettlement(id);
    if (settlement == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(settlement);
  }

  @PostMapping("/{id}/execute")
  public ResponseEntity<?> executeSettlement(@PathVariable long id) {
    var receipt = settlementService.executeSettlement(id);
    if (receipt == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(receipt);
  }

  @PostMapping("/{id}/confirm")
  public ResponseEntity<?> confirmSettlement(@PathVariable long id,
      @RequestParam String bankTransactionId) {
    settlementService.confirmSettlement(id, bankTransactionId);
    return ResponseEntity.ok().build();
  }

  record SettlementInitiateRequest(String currency, long amountCrypto, String bankAccount) {}
}
