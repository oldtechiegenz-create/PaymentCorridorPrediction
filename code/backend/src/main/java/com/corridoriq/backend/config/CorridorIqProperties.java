package com.corridoriq.backend.config;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code corridoriq.*} in application.properties. Paths are plain strings, resolved
 * against the working directory, so a relative path never turns into a classpath lookup.
 */
@ConfigurationProperties("corridoriq")
public record CorridorIqProperties(String db, String sql, String portal, Prediction prediction) {

    public record Prediction(String url, Duration connectTimeout, Duration readTimeout) {
    }

    public Path dbPath() {
        return Path.of(db).toAbsolutePath().normalize();
    }

    public Path sqlPath() {
        return Path.of(sql).toAbsolutePath().normalize();
    }

    public Path portalPath() {
        return Path.of(portal).toAbsolutePath().normalize();
    }
}
