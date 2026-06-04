package dev.nivic.ledger;

import dev.nivic.coa.report.FundFlowReports;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class ReportingController {

  private final FundFlowReports fundFlowReports;

  public ReportingController(FundFlowReports fundFlowReports) {
    this.fundFlowReports = fundFlowReports;
  }

  @GetMapping("/trial-balance")
  public ResponseEntity<?> trialBalance() {
    return ResponseEntity.ok(fundFlowReports.trialBalance());
  }

  @GetMapping("/profit-and-loss")
  public ResponseEntity<?> profitAndLoss() {
    return ResponseEntity.ok(fundFlowReports.profitAndLoss());
  }

  @GetMapping("/cash-flow")
  public ResponseEntity<?> cashFlow() {
    return ResponseEntity.ok(fundFlowReports.cashFlow());
  }

  @GetMapping("/cash-flow/statement")
  public ResponseEntity<?> cashFlowStatement() {
    return ResponseEntity.ok(fundFlowReports.cashFlowStatement());
  }
}
