package dev.nivic.ledger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bank")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class BankController {

  private final BankTransferService bankTransferService;

  public BankController(BankTransferService bankTransferService) {
    this.bankTransferService = bankTransferService;
  }

  @PostMapping("/accounts/register")
  public ResponseEntity<?> registerBankAccount(@RequestBody RegisterBankAccountRequest req) {
    var account = bankTransferService.registerBankAccount(
        req.accountNumber,
        req.bankCode,
        req.bankName,
        req.accountHolder,
        req.currency,
        req.accountType
    );
    return ResponseEntity.ok(account);
  }

  @GetMapping("/accounts/{id}")
  public ResponseEntity<?> getBankAccount(@PathVariable long id) {
    var account = bankTransferService.getBankAccount(id);
    if (account == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(account);
  }

  @PostMapping("/accounts/{id}/activate")
  public ResponseEntity<?> activateBankAccount(@PathVariable long id) {
    bankTransferService.activateBankAccount(id);
    return ResponseEntity.ok().build();
  }

  @PostMapping("/transfers/initiate")
  public ResponseEntity<?> initiateTransfer(@RequestBody InitiateTransferRequest req) {
    var transfer = bankTransferService.initiateTransfer(
        req.bankAccountId,
        req.amountMinor,
        req.currency,
        req.settlementId
    );
    return ResponseEntity.ok(transfer);
  }

  @PostMapping("/transfers/{id}/execute")
  public ResponseEntity<?> executeTransfer(@PathVariable long id) {
    var receipt = bankTransferService.executeTransfer(id);
    if (receipt == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(receipt);
  }

  @GetMapping("/transfers/{id}")
  public ResponseEntity<?> getTransfer(@PathVariable long id) {
    var transfer = bankTransferService.getTransfer(id);
    if (transfer == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(transfer);
  }

  @PostMapping("/transfers/{id}/confirm")
  public ResponseEntity<?> confirmTransfer(@PathVariable long id) {
    bankTransferService.confirmTransfer(id);
    return ResponseEntity.ok().build();
  }

  @GetMapping("/transfers/pending")
  public ResponseEntity<?> getPendingTransfers() {
    return ResponseEntity.ok(bankTransferService.getPendingTransfers());
  }

  public static record RegisterBankAccountRequest(
      String accountNumber,
      String bankCode,
      String bankName,
      String accountHolder,
      String currency,
      String accountType
  ) {}

  public static record InitiateTransferRequest(
      long bankAccountId,
      long amountMinor,
      String currency,
      long settlementId
  ) {}
}
