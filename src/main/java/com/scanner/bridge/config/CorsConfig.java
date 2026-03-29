package com.scanner.bridge.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.List;

/**
 * HTTP CORS configuration for the REST endpoints exposed by the bridge service.
 *
 * <p>In addition to the standard CORS headers, every response carries
 * {@code Access-Control-Allow-Private-Network: true} so that Chrome's
 * <a href="https://wicg.github.io/private-network-access/">Private Network Access</a>
 * preflight checks succeed when the web application is served from a public HTTPS
 * origin and needs to call this localhost service.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * Allowed HTTP origins, read from {@code scanner.allowed-origins} in
     * {@code application.yml}.  Defaults to an empty list if the property is absent.
     */
    @Value("${scanner.allowed-origins:}")
    private List<String> allowedOrigins;

    // -------------------------------------------------------------------------
    // WebMvcConfigurer — Spring MVC CORS
    // -------------------------------------------------------------------------

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = allowedOrigins.isEmpty()
                ? new String[]{"http://localhost:3000"}
                : allowedOrigins.toArray(String[]::new);

        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD")
                .allowedHeaders("*")
                // Expose the PNA header so browsers can read it if needed
                .exposedHeaders("Access-Control-Allow-Private-Network")
                .allowCredentials(false)
                .maxAge(3600);
    }

    // -------------------------------------------------------------------------
    // Servlet filter — injects the PNA header on every HTTP response
    // -------------------------------------------------------------------------

    /**
     * Adds {@code Access-Control-Allow-Private-Network: true} to every HTTP
     * response, including preflight OPTIONS requests.  This ensures that both
     * the standard CORS preflight and Chrome's PNA preflight are satisfied in
     * a single round-trip.
     */
    @Bean
    public OncePerRequestFilter privateNetworkAccessFilter() {
        return new OncePerRequestFilter() {

            private static final String PNA_REQUEST_HEADER  = "Access-Control-Request-Private-Network";
            private static final String PNA_RESPONSE_HEADER = "Access-Control-Allow-Private-Network";

            @Override
            protected void doFilterInternal(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    FilterChain filterChain) throws ServletException, IOException {

                // Always set the header; harmless for non-PNA requests.
                response.setHeader(PNA_RESPONSE_HEADER, "true");

                filterChain.doFilter(request, response);
            }
        };
    }
}
