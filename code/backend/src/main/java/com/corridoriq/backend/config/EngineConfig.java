package com.corridoriq.backend.config;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.corridoriq.backend.engine.BankCalendar;

/** The banking calendar the route simulation uses: the holidays and bank closures stored in the history database. */
@Configuration(proxyBeanMethods = false)
public class EngineConfig {

    @Bean
    public BankCalendar bankCalendar(DataSource historyDataSource) throws SQLException {
        try (Connection c = historyDataSource.getConnection()) {
            return BankCalendar.fromDatabase(c);
        }
    }
}
