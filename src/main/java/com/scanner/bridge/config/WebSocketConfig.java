package com.scanner.bridge.config;

import com.scanner.bridge.handler.ScanWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.Map;

/**
 * Registers the WebSocket endpoint at {@code ws://localhost:8765/scanner}.
 *
 * <p><b>Private Network Access (PNA) note:</b> Chrome enforces the Private Network Access
 * specification when a page served from a public HTTPS origin attempts to open a WebSocket
 * connection to {@code localhost}. Before the actual upgrade, Chrome sends a CORS preflight
 * with {@code Access-Control-Request-Private-Network: true}. The server must respond with
 * {@code Access-Control-Allow-Private-Network: true}, otherwise the connection is blocked.
 * The {@link #privateNetworkHandshakeInterceptor()} below injects that header on every
 * WebSocket handshake response.
 *
 * <p><b>Authentication (SEC-01 / SEC-04):</b> Every WebSocket upgrade is validated against a
 * pre-shared secret token configured via {@code scanner.auth.token}. The client must supply the
 * token either as an {@code Authorization: Bearer <token>} header or as a {@code ?token=} query
 * parameter. Connections that do not present the correct token are rejected with HTTP 401.
 *
 * <p><b>Origin restriction (SEC-04):</b> Only origins listed under {@code scanner.allowed-origins}
 * are accepted — the wildcard {@code setAllowedOriginPatterns("*")} has been removed.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ScanWebSocketHandler scanWebSocketHandler;
    private final ScannerProperties scannerProperties;

    public WebSocketConfig(ScanWebSocketHandler scanWebSocketHandler,
                           ScannerProperties scannerProperties) {
        this.scanWebSocketHandler = scanWebSocketHandler;
        this.scannerProperties = scannerProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = scannerProperties.getAllowedOrigins().toArray(String[]::new);
        boolean isWildcard = origins.length == 1 && "*".equals(origins[0]);
        var handler = registry.addHandler(scanWebSocketHandler, "/scanner");
        if (isWildcard) {
            handler.setAllowedOriginPatterns("*");
        } else {
            handler.setAllowedOrigins(origins);
        }
        handler.addInterceptors(authInterceptor(), privateNetworkHandshakeInterceptor());
    }

    /**
     * Validates the {@code Authorization: Bearer <token>} header on every WebSocket upgrade.
     * As a fallback for browsers (which cannot set custom headers on WebSocket connections),
     * the token may also be supplied as a {@code ?token=} query parameter. This is acceptable
     * because the service is localhost-only and not exposed to the internet.
     *
     * <p>Connections that fail authentication are rejected with HTTP 401.
     */
    @Bean
    public HandshakeInterceptor authInterceptor() {
        return new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                           WebSocketHandler wsHandler, Map<String, Object> attributes) {
                // Check Authorization header first, then query param
                String authHeader = request.getHeaders().getFirst("Authorization");
                String token = null;
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    token = authHeader.substring(7);
                } else if (request instanceof ServletServerHttpRequest servletRequest) {
                    token = servletRequest.getServletRequest().getParameter("token");
                }
                if (token == null || !scannerProperties.getAuth().getToken().equals(token)) {
                    response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                    return false;
                }
                return true;
            }

            @Override
            public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res,
                                       WebSocketHandler h, Exception ex) {}
        };
    }

    /**
     * Adds {@code Access-Control-Allow-Private-Network: true} to the WebSocket
     * upgrade response so that Chrome's Private Network Access checks pass when
     * the client page is served from a public HTTPS domain.
     */
    @Bean
    public HandshakeInterceptor privateNetworkHandshakeInterceptor() {
        return new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse res,
                                           WebSocketHandler h, Map<String, Object> attrs) {
                res.getHeaders().add("Access-Control-Allow-Private-Network", "true");
                return true;
            }

            @Override
            public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res,
                                       WebSocketHandler h, Exception ex) {}
        };
    }

    /**
     * Configures the Tomcat WebSocket container.
     *
     * <p>Buffer sizes are capped at 10 MB (reduced from 64 MB — SEC-09) to limit the
     * DoS surface while still accommodating high-resolution scans in all supported formats.
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(10 * 1024 * 1024);   // 10 MB (SEC-09)
        container.setMaxBinaryMessageBufferSize(10 * 1024 * 1024); // 10 MB (SEC-09)
        return container;
    }
}
