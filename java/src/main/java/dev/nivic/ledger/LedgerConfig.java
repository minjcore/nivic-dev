package dev.nivic.ledger;

import dev.nivic.coa.FundFlowLedger;
import dev.nivic.coa.JdbcFundFlowLedger;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LedgerConfig {

  @Bean
  public FundFlowLedger fundFlowLedger(DataSource dataSource) {
    return new JdbcFundFlowLedger(dataSource);
  }
}
