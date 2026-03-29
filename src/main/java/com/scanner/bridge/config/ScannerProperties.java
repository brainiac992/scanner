package com.scanner.bridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Typed configuration properties for the {@code scanner} namespace.
 *
 * <p>Using {@code @ConfigurationProperties} (rather than {@code @Value}) allows Spring Boot
 * to bind YAML list sequences (e.g. {@code allowed-origins}) correctly.
 */
@Component
@ConfigurationProperties(prefix = "scanner")
public class ScannerProperties {

    private List<String> allowedOrigins = List.of("http://localhost:3000", "http://localhost:5173");
    private Auth auth = new Auth();

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    public static class Auth {
        private String token = "";

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }
}
