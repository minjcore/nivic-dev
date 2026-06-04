package dev.nivic.ledger;

import dev.nivic.coa.report.FundFlowReports;
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
  private final FundFlowReports fundFlowReports;

  public BalanceSheetController(BalanceSheetService balanceSheetService, FundFlowReports fundFlowReports) {
    this.balanceSheetService = balanceSheetService;
    this.fundFlowReports = fundFlowReports;
  }

  @GetMapping
  public ResponseEntity<BalanceSheetResponseDTO> getBalanceSheet() {
    var coreBalance = fundFlowReports.balanceSheet();
    var detailedBalance = balanceSheetService.getBalanceSheetWithDetails();
    return ResponseEntity.ok(new BalanceSheetResponseDTO(
        System.currentTimeMillis(),
        coreBalance,
        detailedBalance.assets,
        detailedBalance.liabilities,
        detailedBalance.equity,
        detailedBalance.revenue,
        detailedBalance.expense,
        detailedBalance.transit
    ));
  }
}
