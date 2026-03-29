package com.scanner.bridge.handler;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scanner.bridge.converter.FileConverter;
import com.scanner.bridge.model.ScanFormat;
import com.scanner.bridge.scanner.ScannerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket handler that bridges the React client to the WIA scanner and file conversion pipeline.
 *
 * <p>Incoming JSON messages are dispatched to {@link ScannerService} and {@link FileConverter}.
 * Because a scan can take 5–30 seconds, all scan work is offloaded to {@link #SCAN_EXECUTOR} via
 * {@link CompletableFuture#runAsync} so the WebSocket handler thread is never blocked.
 *
 * <p>Note: if you prefer Spring's {@code @Async} annotation instead of
 * {@code CompletableFuture.runAsync}, add {@code @EnableAsync} to a {@code @Configuration} class
 * and annotate the relevant method with {@code @Async}. The {@code runAsync} approach used here
 * requires no extra Spring configuration.
 */
@Component
public class ScanWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ScanWebSocketHandler.class);

    // SEC-06: whitelist of accepted action values
    private static final Set<String> VALID_ACTIONS = Set.of("scan", "list");

    /** Dedicated thread pool for async scan tasks — limits concurrency to 4 simultaneous scans. */
    private static final ExecutorService SCAN_EXECUTOR = Executors.newFixedThreadPool(4);

    // SEC-02: per-session and global scan counters for rate limiting
    private static final int MAX_CONCURRENT_SCANS = 1; // per session
    private static final int MAX_QUEUED_SCANS_GLOBAL = 4; // system-wide (matches pool size)
    private static final ConcurrentHashMap<String, AtomicInteger> sessionScanCount = new ConcurrentHashMap<>();
    private static final AtomicInteger globalScanCount = new AtomicInteger(0);

    private final ScannerService scannerService;
    private final FileConverter fileConverter;
    private final ObjectMapper objectMapper;

    public ScanWebSocketHandler(ScannerService scannerService,
                                FileConverter fileConverter,
                                ObjectMapper objectMapper) {
        this.scannerService = scannerService;
        this.fileConverter = fileConverter;
        // SEC-11: configure Jackson size limits to prevent DoS via deeply nested or oversized JSON
        objectMapper.getFactory().setStreamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(10)
                .maxStringLength(10_000)
                .maxNumberLength(20)
                .build()
        );
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Connection lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessionScanCount.put(session.getId(), new AtomicInteger(0)); // SEC-02
        log.info("WebSocket connection established — session id: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessionScanCount.remove(session.getId()); // SEC-02
        log.info("WebSocket connection closed — session id: {}, status: {}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("Transport error on session {}: {}", session.getId(), exception.getMessage(), exception);
        session.close(CloseStatus.SERVER_ERROR);
    }

    // -------------------------------------------------------------------------
    // Message dispatch
    // -------------------------------------------------------------------------

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // SEC-11: reject oversized messages before parsing
        if (message.getPayloadLength() > 1024 * 1024) { // 1 MB max incoming message
            sendJson(session, Map.of("status", "error", "message", "Request too large"));
            return;
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(message.getPayload());
        } catch (Exception ex) {
            log.warn("Malformed JSON received on session {}: {}", session.getId(), ex.getMessage());
            // SEC-07: do not echo raw exception details to the client
            sendJson(session, Map.of("status", "error", "message", "Invalid request format"));
            return;
        }

        String action = root.path("action").asText("");

        // SEC-06: reject unknown actions before dispatch
        if (!VALID_ACTIONS.contains(action)) {
            log.warn("Unknown action '{}' received on session {}", action, session.getId());
            sendJson(session, Map.of("status", "error", "message", "Unknown action"));
            return;
        }

        switch (action) {
            case "scan" -> handleScan(session, root);
            case "list" -> handleList(session);
        }
    }

    // -------------------------------------------------------------------------
    // Action handlers
    // -------------------------------------------------------------------------

    /**
     * Validates the requested format, then performs the scan and conversion asynchronously.
     * The response (success or error) is sent back to the client from the async thread.
     */
    private void handleScan(WebSocketSession session, JsonNode root) throws IOException {
        String formatStr = root.path("format").asText("").toLowerCase();

        // SEC-05: validate format against the canonical ScanFormat enum
        Optional<ScanFormat> scanFormat = ScanFormat.fromKey(formatStr);
        if (scanFormat.isEmpty()) {
            log.warn("Invalid format '{}' requested on session {}", formatStr, session.getId());
            sendJson(session, Map.of("status", "error", "message", "Unsupported format"));
            return;
        }

        // SEC-02: per-session and global rate limiting
        AtomicInteger sessionCount = sessionScanCount.get(session.getId());
        if (sessionCount == null || sessionCount.get() >= MAX_CONCURRENT_SCANS) {
            sendJson(session, Map.of("status", "error", "message", "Scan already in progress. Please wait."));
            return;
        }
        if (globalScanCount.get() >= MAX_QUEUED_SCANS_GLOBAL) {
            sendJson(session, Map.of("status", "error", "message", "Scanner is busy. Please try again shortly."));
            return;
        }

        // SEC-14: reject new scans if heap is dangerously low
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        if ((double) usedMemory / maxMemory > 0.90) {
            sendJson(session, Map.of("status", "error",
                    "message", "Server is under heavy load. Please try again in a moment."));
            return;
        }

        sessionCount.incrementAndGet();
        globalScanCount.incrementAndGet();

        log.info("Scan requested — format: {}, session: {}", scanFormat.get().getKey(), session.getId());

        // Offload the blocking scan+convert work to the dedicated scan thread pool.
        CompletableFuture.runAsync(() -> {
            try {
                // 1. Acquire raw BMP bytes from the scanner.
                byte[] rawBytes = scannerService.scan();

                // 2. Convert to the requested format.
                byte[] convertedBytes = fileConverter.convert(rawBytes, scanFormat.get());

                // 3. Encode as Base64 for transport over the WebSocket text channel.
                String base64Data = Base64.getEncoder().encodeToString(convertedBytes);

                // 4. Collect metadata.
                String mimeType = fileConverter.getMimeType(scanFormat.get());
                String extension = fileConverter.getFileExtension(scanFormat.get());
                String datestamp = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                String filename = "scan_" + datestamp + "_" + System.currentTimeMillis() + "." + extension;

                log.info("Scan complete — filename: {}, size: {} bytes (Base64: {} chars)",
                        filename, convertedBytes.length, base64Data.length());

                // 5. Guard against the client disconnecting while the scan was running.
                if (!session.isOpen()) {
                    log.warn("Session {} closed before scan result could be sent", session.getId());
                    return;
                }

                // 6. Send success response.
                sendJson(session, Map.of(
                        "status", "success",
                        "format", scanFormat.get().getKey(),
                        "mimeType", mimeType,
                        "filename", filename,
                        "data", base64Data
                ));

            } catch (Exception ex) {
                log.error("Scan failed on session {}: {}", session.getId(), ex.getMessage(), ex);
                if (!session.isOpen()) {
                    log.warn("Session {} closed before scan error could be sent", session.getId());
                    return;
                }
                try {
                    // SEC-07: sanitize exception details before sending to client
                    sendJson(session, Map.of("status", "error", "message", sanitizeError(ex)));
                } catch (IOException ioEx) {
                    log.error("Failed to send error response to session {}: {}",
                            session.getId(), ioEx.getMessage(), ioEx);
                }
            } finally {
                // SEC-02: always release the rate-limit counters
                if (sessionCount != null) sessionCount.decrementAndGet();
                globalScanCount.decrementAndGet();
            }
        }, SCAN_EXECUTOR);
    }

    /**
     * Enumerates available WIA scanner devices and returns their names to the client.
     * Device enumeration is fast enough to run on the handler thread.
     */
    private void handleList(WebSocketSession session) throws IOException {
        log.info("List scanners requested — session: {}", session.getId());
        try {
            List<String> scanners = scannerService.listScanners();
            log.info("Found {} scanner(s) for session {}", scanners.size(), session.getId());
            sendJson(session, Map.of(
                    "status", "success",
                    "action", "list",
                    "scanners", scanners
            ));
        } catch (Exception ex) {
            log.error("Failed to list scanners on session {}: {}", session.getId(), ex.getMessage(), ex);
            // SEC-07: do not leak internal error details to the client
            sendJson(session, Map.of("status", "error",
                    "message", "Failed to list scanners. Please ensure the scanner is connected."));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns a client-safe error message.
     *
     * <p>Full exception details are always logged server-side. Only controlled messages from
     * {@link IllegalArgumentException} (thrown by our own validation code) are forwarded to
     * the client verbatim; all other exceptions produce a generic message (SEC-07).
     */
    private String sanitizeError(Exception ex) {
        if (ex instanceof IllegalArgumentException) {
            return ex.getMessage(); // our own controlled messages, safe to return
        }
        // COM errors, IO errors, and any other unexpected exceptions: generic message only
        return "Scan failed. Please ensure the scanner is connected and try again.";
    }

    /**
     * Serialises {@code payload} to JSON and sends it as a WebSocket text frame.
     *
     * @param session the active WebSocket session
     * @param payload any Jackson-serialisable object (e.g. a {@link Map})
     * @throws IOException if serialisation or the send itself fails
     */
    private void sendJson(WebSocketSession session, Object payload) throws IOException {
        String json = objectMapper.writeValueAsString(payload);
        session.sendMessage(new TextMessage(json));
    }
}
