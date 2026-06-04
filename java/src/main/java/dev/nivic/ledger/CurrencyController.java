package dev.nivic.ledger;

import dev.nivic.coa.CurrencyManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/currencies")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class CurrencyController {
  @Autowired
  private CurrencyManager currencyManager;

  public CurrencyController() {
  }

  @GetMapping
  public ResponseEntity<?> listActive() {
    return ResponseEntity.ok(currencyManager.listActive());
  }

  @GetMapping("/{code}")
  public ResponseEntity<?> getCurrency(@PathVariable String code) {
    var currency = currencyManager.find(code);
    if (currency.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(currency.get());
  }

  @PostMapping
  public ResponseEntity<?> registerCurrency(@RequestBody RegisterRequest req) {
    var currency = currencyManager.register(
        req.code, req.name, req.symbol, req.decimalPlaces, req.type, req.blockchain
    );
    return ResponseEntity.ok(currency);
  }

  @PostMapping("/{code}/activate")
  public ResponseEntity<?> activate(@PathVariable String code) {
    currencyManager.activate(code);
    return ResponseEntity.ok().build();
  }

  @PostMapping("/{code}/deactivate")
  public ResponseEntity<?> deactivate(@PathVariable String code) {
    currencyManager.deactivate(code);
    return ResponseEntity.ok().build();
  }

  record RegisterRequest(String code, String name, String symbol, int decimalPlaces, String type, String blockchain) {}
}
