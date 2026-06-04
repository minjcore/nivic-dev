package dev.nivic.coa;

/**
 * Currency metadata: supported currencies with decimals, symbols, regions.
 * Supports crypto (USDT, USDC, ETH, BTC) and fiat (VND, USD, etc).
 */
public record Currency(
    String code,                 // "USDT", "VND", "ETH", etc
    String name,                 // "Tether", "Vietnamese Dong", etc
    String symbol,               // "$", "₫", "Ξ"
    int decimalPlaces,           // 18 for USDT (wei), 2 for VND (cents)
    String type,                 // "CRYPTO" or "FIAT"
    String blockchain,           // "ETHEREUM", "BITCOIN", null for fiat
    boolean active
) {
  public static Currency usdt() {
    return new Currency("USDT", "Tether", "$", 18, "CRYPTO", "ETHEREUM", true);
  }

  public static Currency usdc() {
    return new Currency("USDC", "USD Coin", "$", 18, "CRYPTO", "ETHEREUM", true);
  }

  public static Currency eth() {
    return new Currency("ETH", "Ethereum", "Ξ", 18, "CRYPTO", "ETHEREUM", true);
  }

  public static Currency btc() {
    return new Currency("BTC", "Bitcoin", "₿", 8, "CRYPTO", "BITCOIN", true);
  }

  public static Currency vnd() {
    return new Currency("VND", "Vietnamese Dong", "₫", 0, "FIAT", null, true);
  }

  public static Currency usd() {
    return new Currency("USD", "United States Dollar", "$", 2, "FIAT", null, true);
  }
}
