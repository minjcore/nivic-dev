package dev.nivic.ledger;

import java.util.Date;

public class BalanceSheetDTO {
  public Date timestamp;
  public BalanceSheetService.AccountTypeGroup assets;
  public BalanceSheetService.AccountTypeGroup liabilities;
  public BalanceSheetService.AccountTypeGroup equity;
  public BalanceSheetService.AccountTypeGroup revenue;
  public BalanceSheetService.AccountTypeGroup expense;
  public BalanceSheetService.AccountTypeGroup transit;

  public BalanceSheetDTO(
      Date timestamp,
      BalanceSheetService.AccountTypeGroup assets,
      BalanceSheetService.AccountTypeGroup liabilities,
      BalanceSheetService.AccountTypeGroup equity,
      BalanceSheetService.AccountTypeGroup revenue,
      BalanceSheetService.AccountTypeGroup expense,
      BalanceSheetService.AccountTypeGroup transit
  ) {
    this.timestamp = timestamp;
    this.assets = assets;
    this.liabilities = liabilities;
    this.equity = equity;
    this.revenue = revenue;
    this.expense = expense;
    this.transit = transit;
  }
}
