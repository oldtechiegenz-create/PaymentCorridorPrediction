package com.corridoriq.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * CorridorIQ backend: serves the portal, the history API behind the Historical Cases tab, and the
 * route-ranking endpoint, which it forwards to the Python prediction service that hosts the ML models.
 *
 * <pre>
 *   cd code/backend
 *   ./mvnw spring-boot:run          # portal at http://127.0.0.1:8000
 * </pre>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CorridorIqApplication {

    public static void main(String[] args) {
        SpringApplication.run(CorridorIqApplication.class, args);
    }
}
