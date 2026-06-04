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

  public BalanceSheetDTO getBalanceSheet() {
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

    return new BalanceSheetDTO(
        new Date(),
        grouped.get("ASSET") != null ? grouped.get("ASSET") : new AccountTypeGroup("ASSET", new ArrayList<>(), BigDecimal.ZERO),
        grouped.get("LIABILITY") != null ? grouped.get("LIABILITY") : new AccountTypeGroup("LIABILITY", new ArrayList<>(), BigDecimal.ZERO),
        grouped.get("EQUITY") != null ? grouped.get("EQUITY") : new AccountTypeGroup("EQUITY", new ArrayList<>(), BigDecimal.ZERO),
        grouped.get("REVENUE") != null ? grouped.get("REVENUE") : new AccountTypeGroup("REVENUE", new ArrayList<>(), BigDecimal.ZERO),
        grouped.get("EXPENSE") != null ? grouped.get("EXPENSE") : new AccountTypeGroup("EXPENSE", new ArrayList<>(), BigDecimal.ZERO),
        grouped.get("TRANSIT") != null ? grouped.get("TRANSIT") : new AccountTypeGroup("TRANSIT", new ArrayList<>(), BigDecimal.ZERO)
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
