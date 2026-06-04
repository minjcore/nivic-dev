package dev.nivic.ledger;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transactions")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class TransactionController {

  private final TransactionQueryService transactionQueryService;

  public TransactionController(TransactionQueryService transactionQueryService) {
    this.transactionQueryService = transactionQueryService;
  }

  @GetMapping
  public ResponseEntity<?> queryTransactions(
      @RequestParam(required = false) String account,
      @RequestParam(required = false) String ref) {

    if (account != null) {
      return ResponseEntity.ok(transactionQueryService.findByAccount(account));
    }
    if (ref != null) {
      return ResponseEntity.ok(transactionQueryService.findByRefId(ref));
    }
    return ResponseEntity.badRequest().body("Provide either ?account=CODE or ?ref=PATTERN");
  }

  @GetMapping("/{id}")
  public ResponseEntity<?> getTransaction(@PathVariable long id) {
    try {
      return ResponseEntity.ok(transactionQueryService.getTransaction(id));
    } catch (Exception e) {
      return ResponseEntity.notFound().build();
    }
  }
}
