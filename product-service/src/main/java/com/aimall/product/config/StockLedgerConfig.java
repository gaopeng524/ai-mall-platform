package com.aimall.product.config;

import com.aimall.common.stock.JdbcStockLedger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class StockLedgerConfig {

    @Bean
    public JdbcStockLedger jdbcStockLedger(JdbcTemplate jdbcTemplate) {
        return new JdbcStockLedger(jdbcTemplate);
    }
}
