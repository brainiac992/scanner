package com.scanner.bridge.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.io.IOException;

/**
 * Adds HTTP security headers to all responses.
 * Mitigates clickjacking, MIME sniffing, and XSS attacks (SEC-18).
 */
@Component
@Order(1)
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        // Prevent MIME type sniffing
        httpResponse.setHeader("X-Content-Type-Options", "nosniff");
        // Prevent clickjacking
        httpResponse.setHeader("X-Frame-Options", "DENY");
        // Basic XSS protection (legacy browsers)
        httpResponse.setHeader("X-XSS-Protection", "1; mode=block");
        // Content Security Policy — this is a localhost API, not a web page, so restrictive CSP is appropriate
        httpResponse.setHeader("Content-Security-Policy", "default-src 'none'");
        // Do not cache responses (scanned documents should not be cached by proxies)
        httpResponse.setHeader("Cache-Control", "no-store");
        httpResponse.setHeader("Pragma", "no-cache");
        chain.doFilter(request, response);
    }
}
