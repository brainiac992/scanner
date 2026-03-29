package com.scanner.bridge.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import com.scanner.bridge.BridgeApplication;

import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for the WebSocket scan pipeline.
 *
 * <p>Boots the full Spring application context with the {@code test} profile active,
 * which causes {@link com.scanner.bridge.scanner.MockScannerImpl} to be used in place
 * of {@link com.scanner.bridge.scanner.WiaScanner}. A real WebSocket client connects
 * to the randomly assigned server port, sends JSON action messages, and asserts on the
 * JSON responses including format-specific binary magic numbers decoded from Base64.
 */
@SpringBootTest(
        classes = BridgeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
class WebSocketIntegrationTest {

    @LocalServerPort
    int port;

    private WebSocketSession session;
    private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @BeforeEach
    void connect() throws Exception {
        // Configure the Tyrus WebSocket client container with a 64 MB receive buffer so it
        // can handle large Base64-encoded scan payloads without triggering a 1009 close.
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(64 * 1024 * 1024);
        container.setDefaultMaxBinaryMessageBufferSize(64 * 1024 * 1024);
        WebSocketClient client = new StandardWebSocketClient(container);
        // The auth interceptor requires a token; supply the test-profile token as a query param.
        String url = "ws://localhost:" + port + "/scanner?token=test-token-not-for-production";

        session = client.execute(new AbstractWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage msg) {
                messages.add(msg.getPayload());
            }
        }, url).get(5, TimeUnit.SECONDS);

        // Allow the connection handshake to fully stabilise before sending messages.
        Thread.sleep(200);
    }

    @AfterEach
    void disconnect() throws Exception {
        if (session != null && session.isOpen()) {
            session.close();
        }
        messages.clear();
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Sends a raw JSON string over the active WebSocket session.
     */
    private void send(String json) throws Exception {
        session.sendMessage(new TextMessage(json));
    }

    /**
     * Waits up to {@code timeoutSeconds} for the next message from the server and
     * parses it as a {@link JsonNode}. Fails the test immediately if no message
     * arrives within the timeout.
     */
    private JsonNode pollResponse(long timeoutSeconds) throws Exception {
        String raw = messages.poll(timeoutSeconds, TimeUnit.SECONDS);
        assertNotNull(raw, "Timed out waiting for WebSocket response after " + timeoutSeconds + "s");
        return objectMapper.readTree(raw);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * Sending {@code {"action":"list"}} must return a success response whose
     * {@code scanners} array contains the mock scanner's display name.
     */
    @Test
    void test_listScanners_returnsVirtualScanner() throws Exception {
        send("{\"action\":\"list\"}");

        JsonNode response = pollResponse(3);

        assertEquals("success", response.path("status").asText(),
                "Expected status=success in list response");

        JsonNode scanners = response.path("scanners");
        assertTrue(scanners.isArray(), "Expected 'scanners' to be a JSON array");
        assertTrue(scanners.size() > 0, "Expected at least one scanner in the list");

        boolean found = false;
        for (JsonNode scanner : scanners) {
            if ("Virtual Scanner (Test Mode)".equals(scanner.asText())) {
                found = true;
                break;
            }
        }
        assertTrue(found,
                "Expected 'Virtual Scanner (Test Mode)' in scanners list but got: " + scanners);
    }

    /**
     * A PDF scan must return a success response with the correct MIME type and
     * Base64-encoded bytes whose first four bytes are the {@code %PDF} magic signature.
     */
    @Test
    void test_scan_pdf_returnsValidPdfBase64() throws Exception {
        send("{\"action\":\"scan\",\"format\":\"pdf\"}");

        // PDF conversion includes PDFBox rendering — allow extra time.
        JsonNode response = pollResponse(15);

        assertEquals("success", response.path("status").asText(),
                "Expected status=success for PDF scan");
        assertEquals("pdf", response.path("format").asText(),
                "Expected format=pdf in response");
        assertEquals("application/pdf", response.path("mimeType").asText(),
                "Expected mimeType=application/pdf");

        String data = response.path("data").asText();
        assertFalse(data.isEmpty(), "Expected non-empty base64 data field");

        byte[] decoded = Base64.getDecoder().decode(data);
        assertTrue(decoded.length >= 4, "Decoded PDF bytes too short to contain magic number");

        // %PDF == 0x25 0x50 0x44 0x46
        assertEquals((byte) 0x25, decoded[0], "PDF magic byte 0 mismatch ('%')");
        assertEquals((byte) 0x50, decoded[1], "PDF magic byte 1 mismatch ('P')");
        assertEquals((byte) 0x44, decoded[2], "PDF magic byte 2 mismatch ('D')");
        assertEquals((byte) 0x46, decoded[3], "PDF magic byte 3 mismatch ('F')");
    }

    /**
     * A JPEG scan must return Base64-encoded bytes that start with the JPEG SOI marker
     * {@code 0xFF 0xD8}.
     */
    @Test
    void test_scan_jpeg_returnsValidJpeg() throws Exception {
        send("{\"action\":\"scan\",\"format\":\"jpeg\"}");

        JsonNode response = pollResponse(15);

        assertEquals("success", response.path("status").asText(),
                "Expected status=success for JPEG scan");

        String data = response.path("data").asText();
        assertFalse(data.isEmpty(), "Expected non-empty base64 data field for JPEG");

        byte[] decoded = Base64.getDecoder().decode(data);
        assertTrue(decoded.length >= 2, "Decoded JPEG bytes too short to contain SOI marker");

        // JPEG SOI marker: 0xFF 0xD8
        assertEquals((byte) 0xFF, decoded[0], "JPEG magic byte 0 mismatch (0xFF)");
        assertEquals((byte) 0xD8, decoded[1], "JPEG magic byte 1 mismatch (0xD8)");
    }

    /**
     * A PNG scan must return Base64-encoded bytes that start with the PNG signature
     * {@code 0x89 0x50 0x4E 0x47}.
     */
    @Test
    void test_scan_png_returnsValidPng() throws Exception {
        send("{\"action\":\"scan\",\"format\":\"png\"}");

        JsonNode response = pollResponse(15);

        assertEquals("success", response.path("status").asText(),
                "Expected status=success for PNG scan");

        String data = response.path("data").asText();
        assertFalse(data.isEmpty(), "Expected non-empty base64 data field for PNG");

        byte[] decoded = Base64.getDecoder().decode(data);
        assertTrue(decoded.length >= 4, "Decoded PNG bytes too short to contain signature");

        // PNG signature: 0x89 'P' 'N' 'G'
        assertEquals((byte) 0x89, decoded[0], "PNG magic byte 0 mismatch (0x89)");
        assertEquals((byte) 0x50, decoded[1], "PNG magic byte 1 mismatch ('P')");
        assertEquals((byte) 0x4E, decoded[2], "PNG magic byte 2 mismatch ('N')");
        assertEquals((byte) 0x47, decoded[3], "PNG magic byte 3 mismatch ('G')");
    }

    /**
     * A TIFF scan must return Base64-encoded bytes whose first two bytes are either
     * {@code II} (little-endian, {@code 0x49 0x49}) or {@code MM} (big-endian,
     * {@code 0x4D 0x4D}).
     */
    @Test
    void test_scan_tiff_returnsValidTiff() throws Exception {
        send("{\"action\":\"scan\",\"format\":\"tiff\"}");

        JsonNode response = pollResponse(15);

        assertEquals("success", response.path("status").asText(),
                "Expected status=success for TIFF scan");

        String data = response.path("data").asText();
        assertFalse(data.isEmpty(), "Expected non-empty base64 data field for TIFF");

        byte[] decoded = Base64.getDecoder().decode(data);
        assertTrue(decoded.length >= 2, "Decoded TIFF bytes too short to contain byte-order mark");

        // TIFF byte-order marks: 'II' (little-endian) or 'MM' (big-endian)
        boolean littleEndian = decoded[0] == (byte) 0x49 && decoded[1] == (byte) 0x49;
        boolean bigEndian    = decoded[0] == (byte) 0x4D && decoded[1] == (byte) 0x4D;

        assertTrue(littleEndian || bigEndian,
                String.format("Expected TIFF byte-order mark (II or MM) but got: 0x%02X 0x%02X",
                        decoded[0] & 0xFF, decoded[1] & 0xFF));
    }

    /**
     * Requesting an unsupported format (e.g. {@code bmp}) must return an error response
     * with a non-empty error message. The handler validates formats before dispatching to
     * the async scan thread, so the reply is fast.
     */
    @Test
    void test_invalidFormat_returnsError() throws Exception {
        send("{\"action\":\"scan\",\"format\":\"bmp\"}");

        JsonNode response = pollResponse(3);

        assertEquals("error", response.path("status").asText(),
                "Expected status=error for unsupported format");

        String message = response.path("message").asText();
        assertFalse(message.isEmpty(), "Expected a non-empty error message for invalid format");
    }

    /**
     * An unrecognised action must produce an immediate error response on the same session.
     */
    @Test
    void test_unknownAction_returnsError() throws Exception {
        send("{\"action\":\"unknown\"}");

        JsonNode response = pollResponse(3);

        assertEquals("error", response.path("status").asText(),
                "Expected status=error for unknown action");

        String message = response.path("message").asText();
        assertFalse(message.isEmpty(), "Expected a non-empty error message for unknown action");
    }

    /**
     * Sending text that is not valid JSON must produce an error response rather than
     * crashing the handler or leaving the session in a broken state.
     */
    @Test
    void test_malformedJson_returnsError() throws Exception {
        send("not json at all");

        JsonNode response = pollResponse(3);

        assertEquals("error", response.path("status").asText(),
                "Expected status=error for malformed JSON input");

        String message = response.path("message").asText();
        assertFalse(message.isEmpty(), "Expected a non-empty error message for malformed JSON");
    }
}
