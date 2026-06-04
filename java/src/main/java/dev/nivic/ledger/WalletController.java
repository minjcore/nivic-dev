package dev.nivic.ledger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Wallet REST API: create wallet, transfer A→B, manage holds.
 * NO direct blockchain - all transfers via wallet system.
 */
@RestController
@RequestMapping("/api/wallets")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class WalletController {
  @Autowired
  private WalletManager walletManager;

  @PostMapping
  public ResponseEntity<?> createWallet(@RequestBody CreateWalletRequest req) {
    var wallet = walletManager.createWallet(
        req.uid, req.walletType, req.currency, req.accountCode
    );
    return ResponseEntity.ok(wallet);
  }

  @GetMapping("/{walletId}")
  public ResponseEntity<?> getWallet(@PathVariable long walletId) {
    var wallet = walletManager.getWallet(walletId);
    if (wallet.isEmpty()) return ResponseEntity.notFound().build();
    return ResponseEntity.ok(wallet.get());
  }

  @GetMapping("/{walletId}/balance")
  public ResponseEntity<?> getBalance(@PathVariable long walletId) {
    var wallet = walletManager.getWallet(walletId);
    if (wallet.isEmpty()) return ResponseEntity.notFound().build();
    return ResponseEntity.ok(new BalanceResponse(
        wallet.get().balanceMinor(),
        walletManager.getHeldBalance(walletId),
        walletManager.getAvailableBalance(walletId)
    ));
  }

  @PostMapping("/{walletId}/transfer")
  public ResponseEntity<?> initiateTransfer(
      @PathVariable long walletId,
      @RequestBody TransferRequest req
  ) {
    var toWallet = walletManager.getWallet(req.toWalletId);
    if (toWallet.isEmpty()) return ResponseEntity.notFound().build();

    var fromWallet = walletManager.getWallet(walletId);
    if (fromWallet.isEmpty()) return ResponseEntity.notFound().build();

    if (!fromWallet.get().canTransferOut()) {
      return ResponseEntity.badRequest().body("Wallet status does not allow transfer");
    }

    var transfer = walletManager.initiateTransfer(
        walletId, req.toWalletId, req.amountMinor,
        req.currency, req.refId, req.memo
    );
    return ResponseEntity.ok(transfer);
  }

  @GetMapping("/{walletId}/transfers")
  public ResponseEntity<?> getPendingTransfers(@PathVariable long walletId) {
    return ResponseEntity.ok(walletManager.getPendingTransfers(walletId));
  }

  @PostMapping("/transfer/{transferId}/confirm")
  public ResponseEntity<?> confirmTransfer(@PathVariable long transferId) {
    var transfer = walletManager.getTransfer(transferId);
    if (transfer.isEmpty()) return ResponseEntity.notFound().build();

    walletManager.confirmTransfer(transferId);
    return ResponseEntity.ok().build();
  }

  record CreateWalletRequest(String uid, String walletType, String currency, String accountCode) {}
  record TransferRequest(long toWalletId, long amountMinor, String currency, String refId, String memo) {}
  record BalanceResponse(long total, long held, long available) {}
}
