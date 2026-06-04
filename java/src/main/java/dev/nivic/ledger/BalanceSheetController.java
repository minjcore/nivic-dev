package dev.nivic.ledger;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/balance-sheet")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class BalanceSheetController {

  private final BalanceSheetService balanceSheetService;

  public BalanceSheetController(BalanceSheetService balanceSheetService) {
    this.balanceSheetService = balanceSheetService;
  }

  @GetMapping
  public ResponseEntity<BalanceSheetDTO> getBalanceSheet() {
    return ResponseEntity.ok(balanceSheetService.getBalanceSheet());
  }
}
