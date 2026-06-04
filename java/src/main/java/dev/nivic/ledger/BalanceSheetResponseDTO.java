package dev.nivic.ledger;

import dev.nivic.coa.report.BalanceSheet;

public record BalanceSheetResponseDTO(
    long timestamp,
    BalanceSheet core,
    BalanceSheetService.AccountTypeGroup assets,
    BalanceSheetService.AccountTypeGroup liabilities,
    BalanceSheetService.AccountTypeGroup equity,
    BalanceSheetService.AccountTypeGroup revenue,
    BalanceSheetService.AccountTypeGroup expense,
    BalanceSheetService.AccountTypeGroup transit) {
}
