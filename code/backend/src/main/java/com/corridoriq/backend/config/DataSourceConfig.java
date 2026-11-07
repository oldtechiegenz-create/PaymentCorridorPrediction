package com.corridoriq.backend.config;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Read-only connection pool on the SQLite history database. The backend never writes to it:
 * the Python scripts generate it and recreate its views.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    @Bean
    public DataSource historyDataSource(CorridorIqProperties props) {
        Path db = props.dbPath();
        if (!Files.isRegularFile(db)) {
            throw new IllegalStateException("History database not found at " + db
                    + "; set CORRIDORIQ_DB or generate it with GenerateHistory (see code/README.md)");
        }
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.setReadOnly(true);
        SQLiteDataSource source = new SQLiteDataSource(sqlite);
        source.setUrl("jdbc:sqlite:" + db);

        HikariConfig pool = new HikariConfig();
        pool.setPoolName("history");
        pool.setDataSource(source);
        pool.setMaximumPoolSize(4);
        pool.setReadOnly(true);   // must match the SQLite open mode: sqlite-jdbc refuses to change it later
        return new HikariDataSource(pool);
    }
}
