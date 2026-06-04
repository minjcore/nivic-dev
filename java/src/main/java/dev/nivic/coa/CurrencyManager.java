package dev.nivic.coa;

import java.util.Optional;
import java.util.Set;

public interface CurrencyManager {
  Currency register(String code, String name, String symbol, int decimalPlaces, String type, String blockchain);

  Optional<Currency> find(String code);

  Set<Currency> listActive();

  void activate(String code);

  void deactivate(String code);
}
