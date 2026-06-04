package dev.nivic.ledger;

import dev.nivic.coa.CurrencyManager;
import dev.nivic.coa.FundFlowLedger;
import dev.nivic.coa.JdbcCurrencyManager;
import dev.nivic.coa.JdbcFundFlowLedger;
import dev.nivic.coa.report.FundFlowReports;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LedgerConfig {

  @Bean
  public FundFlowLedger fundFlowLedger(DataSource dataSource) {
    return new JdbcFundFlowLedger(dataSource);
  }

  @Bean
  public FundFlowReports fundFlowReports(DataSource dataSource) {
    return new FundFlowReports(dataSource);
  }

  @Bean
  public CurrencyManager currencyManager(DataSource dataSource) {
    return new JdbcCurrencyManager(dataSource);
  }

  @Bean
  public WalletManager walletManager(DataSource dataSource) {
    return new JdbcWalletManager(dataSource);
  }
}
