package com.scanner.bridge.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scanner.bridge.converter.FileConverter;
import com.scanner.bridge.scanner.ScannerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;

import com.scanner.bridge.model.ScanFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import org.mockito.Mockito;

/**
 * Unit tests for {@link ScanWebSocketHandler}.
 *
 * <p>{@link ScannerService} and {@link FileConverter} are mocked. A real {@link ObjectMapper}
 * is used so JSON serialisation/deserialisation behaviour matches production exactly.
 *
 * <p>Because {@code handleScan} offloads work to {@code CompletableFuture.runAsync}, tests
 * that exercise the async path use a short {@code Thread.sleep} to allow the background
 * task to complete before assertions are made.
 */
@ExtendWith(MockitoExtension.class)
class ScanWebSocketHandlerTest {

    @Mock
    ScannerService scannerService;

    @Mock
    FileConverter fileConverter;

    @Mock
    WebSocketSession session;

    // ObjectMapper is a real instance — @InjectMocks will inject it alongside the mocks.
    // Mockito cannot create a real ObjectMapper via @Mock, so we declare it as a field and
    // let the spy/constructor-injection pick it up.  We build the handler manually instead
    // so we can pass a live ObjectMapper.
    ScanWebSocketHandler handler;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp() throws Exception {
        // Build handler manually because @InjectMocks would try to inject ObjectMapper as a
        // mock, which wouldn't serialise anything.  Passing a real ObjectMapper keeps the
        // handler in the same state as production while still using mocked collaborators.
        handler = new ScanWebSocketHandler(scannerService, fileConverter, objectMapper);
        // Provide a non-null session ID so ConcurrentHashMap operations in the handler don't NPE.
        Mockito.lenient().when(session.getId()).thenReturn("test-session-id");
        // Register the session so the per-session scan counter is initialised.
        handler.afterConnectionEstablished(session);
        // lenient: isOpen() is only checked in the async scan path, not in all tests
        Mockito.lenient().when(session.isOpen()).thenReturn(true);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Dispatches a raw JSON string to the handler as if it arrived over the WebSocket.
     */
    private void send(String json) throws Exception {
        handler.handleTextMessage(session, new TextMessage(json));
    }

    /**
     * Captures the single {@link TextMessage} sent to the session and parses it as JSON.
     */
    private JsonNode captureResponse() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        TextMessage sent = captor.getValue();
        return objectMapper.readTree(sent.getPayload());
    }

    // -------------------------------------------------------------------------
    // Test cases
    // -------------------------------------------------------------------------

    @Test
    void scan_withValidFormat_sendsSuccessResponse() throws Exception {
        when(scannerService.scan(anyInt())).thenReturn(List.of(new byte[]{1, 2, 3}));
        // The handler calls convert(byte[], ScanFormat) and getMimeType/getFileExtension(ScanFormat).
        when(fileConverter.convert(any(byte[].class), any(ScanFormat.class))).thenReturn("PDFDATA".getBytes());
        when(fileConverter.getMimeType(any(ScanFormat.class))).thenReturn("application/pdf");
        when(fileConverter.getFileExtension(any(ScanFormat.class))).thenReturn("pdf");

        send("{\"action\":\"scan\",\"format\":\"pdf\"}");
        Thread.sleep(500);

        verify(session, times(1)).sendMessage(any());

        JsonNode response = captureResponse();
        assertEquals("success", response.path("status").asText(),
                "Response status must be 'success'");
        assertEquals("pdf", response.path("format").asText(),
                "Response format must match requested format");
        assertFalse(response.path("data").asText().isEmpty(),
                "Response data (base64) must not be empty");
    }

    @Test
    void scan_withInvalidFormat_sendsErrorResponse() throws Exception {
        send("{\"action\":\"scan\",\"format\":\"bmp\"}");

        // Format validation is synchronous — no sleep needed.
        verify(session, atLeastOnce()).sendMessage(any());

        JsonNode response = captureResponse();
        assertEquals("error", response.path("status").asText(),
                "Response status must be 'error' for an invalid format");
    }

    @Test
    void scan_whenScannerThrows_sendsErrorResponse() throws Exception {
        // IllegalArgumentException messages pass through sanitizeError() verbatim; a plain
        // Exception would be replaced with a generic message (SEC-07).
        when(scannerService.scan(anyInt())).thenThrow(new IllegalArgumentException("No scanner found"));

        send("{\"action\":\"scan\",\"format\":\"pdf\"}");
        Thread.sleep(500);

        verify(session, atLeastOnce()).sendMessage(any());

        JsonNode response = captureResponse();
        assertEquals("error", response.path("status").asText(),
                "Response status must be 'error' when the scanner throws");
        assertTrue(response.path("message").asText().contains("No scanner found"),
                "Error message should propagate the original exception message");
    }

    @Test
    void list_sendsListResponse() throws Exception {
        when(scannerService.listScanners()).thenReturn(List.of("HP Scanner"));

        send("{\"action\":\"list\"}");

        // handleList is synchronous — no sleep needed.
        verify(session, atLeastOnce()).sendMessage(any());

        JsonNode response = captureResponse();
        assertEquals("success", response.path("status").asText(),
                "Response status must be 'success' for list action");
        assertTrue(response.path("scanners").isArray(),
                "Response must contain a 'scanners' array");
        assertEquals("HP Scanner", response.path("scanners").get(0).asText(),
                "First scanner name must match the mocked value");
    }

    @Test
    void scan_whenSessionClosed_doesNotThrow() throws Exception {
        // Session is closed before the async work completes.
        when(session.isOpen()).thenReturn(false);
        when(scannerService.scan(anyInt())).thenReturn(List.of(new byte[]{1, 2, 3}));
        when(fileConverter.convert(any(byte[].class), any(ScanFormat.class))).thenReturn(new byte[]{1, 2, 3});

        send("{\"action\":\"scan\",\"format\":\"pdf\"}");
        Thread.sleep(500);

        // The handler must silently bail out without sending a message.
        verify(session, never()).sendMessage(any());
    }

    @Test
    void unknownAction_sendsErrorResponse() throws Exception {
        send("{\"action\":\"fly\"}");

        verify(session, atLeastOnce()).sendMessage(any());

        JsonNode response = captureResponse();
        assertEquals("error", response.path("status").asText(),
                "Response status must be 'error' for an unknown action");
        // The handler intentionally does not echo the unknown action name back to the client
        // (SEC-06: avoid leaking internal dispatch details). Assert the generic error message.
        assertTrue(response.path("message").asText().contains("Unknown action"),
                "Error message should be the generic 'Unknown action' string");
    }
}
