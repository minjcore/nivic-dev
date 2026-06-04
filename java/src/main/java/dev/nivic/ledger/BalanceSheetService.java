package dev.nivic.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BalanceSheetService {

  private final JdbcTemplate jdbcTemplate;

  public BalanceSheetService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public DetailedBalance getBalanceSheetWithDetails() {
    List<AccountBalance> accounts = fetchAccounts();

    Map<String, AccountTypeGroup> grouped = accounts.stream()
        .collect(Collectors.groupingBy(
            a -> a.kind,
            Collectors.collectingAndThen(
                Collectors.toList(),
                list -> new AccountTypeGroup(
                    list.get(0).kind,
                    list,
                    list.stream()
                        .map(a -> a.balance)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                )
            )
        ));

    return new DetailedBalance(
        grouped.getOrDefault("ASSET", new AccountTypeGroup("ASSET", new ArrayList<>(), BigDecimal.ZERO)),
        grouped.getOrDefault("LIABILITY", new AccountTypeGroup("LIABILITY", new ArrayList<>(), BigDecimal.ZERO)),
        grouped.getOrDefault("EQUITY", new AccountTypeGroup("EQUITY", new ArrayList<>(), BigDecimal.ZERO)),
        grouped.getOrDefault("REVENUE", new AccountTypeGroup("REVENUE", new ArrayList<>(), BigDecimal.ZERO)),
        grouped.getOrDefault("EXPENSE", new AccountTypeGroup("EXPENSE", new ArrayList<>(), BigDecimal.ZERO)),
        grouped.getOrDefault("TRANSIT", new AccountTypeGroup("TRANSIT", new ArrayList<>(), BigDecimal.ZERO))
    );
  }

  private List<AccountBalance> fetchAccounts() {
    return jdbcTemplate.query(
        "SELECT code, name, kind, currency_code, balance_minor FROM coa_account ORDER BY code",
        (rs, rowNum) -> new AccountBalance(
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("kind"),
            rs.getString("currency_code"),
            new BigDecimal(rs.getLong("balance_minor"))
        )
    );
  }

  public static class DetailedBalance {
    public AccountTypeGroup assets;
    public AccountTypeGroup liabilities;
    public AccountTypeGroup equity;
    public AccountTypeGroup revenue;
    public AccountTypeGroup expense;
    public AccountTypeGroup transit;

    public DetailedBalance(AccountTypeGroup assets, AccountTypeGroup liabilities, AccountTypeGroup equity,
                           AccountTypeGroup revenue, AccountTypeGroup expense, AccountTypeGroup transit) {
      this.assets = assets;
      this.liabilities = liabilities;
      this.equity = equity;
      this.revenue = revenue;
      this.expense = expense;
      this.transit = transit;
    }
  }

  public static class AccountBalance {
    public String code;
    public String name;
    public String kind;
    public String currencyCode;
    public BigDecimal balance;

    public AccountBalance(String code, String name, String kind, String currencyCode, BigDecimal balance) {
      this.code = code;
      this.name = name;
      this.kind = kind;
      this.currencyCode = currencyCode;
      this.balance = balance;
    }
  }

  public static class AccountTypeGroup {
    public String type;
    public List<AccountBalance> accounts;
    public BigDecimal total;

    public AccountTypeGroup(String type, List<AccountBalance> accounts, BigDecimal total) {
      this.type = type;
      this.accounts = accounts;
      this.total = total;
    }
  }
}
