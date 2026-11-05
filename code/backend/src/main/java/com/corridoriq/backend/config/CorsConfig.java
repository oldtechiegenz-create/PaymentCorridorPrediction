package com.corridoriq.backend.config;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * The portal may be opened from disk (Origin: null) or from a local dev server; this is a local MVP
 * service, so only those origins are allowed.
 */
@Configuration(proxyBeanMethods = false)
public class CorsConfig {

    static final Pattern LOCAL_ORIGIN = Pattern.compile("^(null|https?://(localhost|127\\.0\\.0\\.1)(:\\d+)?)$");

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration() {
            @Override
            public String checkOrigin(String origin) {
                return origin != null && LOCAL_ORIGIN.matcher(origin).matches() ? origin : null;
            }
        };
        config.setAllowedMethods(List.of("GET", "POST"));
        config.setAllowedHeaders(List.of("Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
