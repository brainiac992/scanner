import { useState, useEffect, useRef, useCallback } from 'react';

const VALID_FORMATS = ['pdf', 'jpeg', 'png', 'tiff'];
const MAX_RETRIES = 5;
const SCAN_TIMEOUT_MS = 60000; // 60 seconds

/**
 * INTEGRATION NOTES:
 *
 * Before using this hook, the host webapp must set two globals:
 *
 *   window.SCANNER_BRIDGE_URL   — WebSocket URL (default: ws://localhost:8765/scanner)
 *   window.SCANNER_BRIDGE_TOKEN — Pre-shared auth token (required, set by your server)
 *
 * Example (set these before your React bundle loads):
 *   <script>
 *     window.SCANNER_BRIDGE_URL   = 'ws://localhost:8765/scanner';
 *     window.SCANNER_BRIDGE_TOKEN = '{{ server_rendered_token }}';
 *   </script>
 *
 * SECURITY NOTE (SEC-17):
 *   This service is designed for localhost use only.
 *   Do NOT change the URL to a remote host without also enabling WSS (TLS).
 *   Do NOT expose port 8765 to external networks.
 */
export function useScanner(
  bridgeUrl = (typeof window !== 'undefined' && window.SCANNER_BRIDGE_URL)
    ? window.SCANNER_BRIDGE_URL
    : 'ws://localhost:8765/scanner'
) {
  const [status, setStatus] = useState('disconnected');
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);

  const wsRef = useRef(null);
  const mountedRef = useRef(true);
  const reconnectTimerRef = useRef(null);
  const retriesRef = useRef(0);
  const scanTimeoutRef = useRef(null);

  const clearScanTimeout = () => {
    if (scanTimeoutRef.current) {
      clearTimeout(scanTimeoutRef.current);
      scanTimeoutRef.current = null;
    }
  };

  const connect = useCallback(() => {
    if (!mountedRef.current) return;
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) return;

    setStatus('connecting');
    // SEC-01: append the pre-shared token as a query parameter (browser WS API has no header support)
    const token = (typeof window !== 'undefined' && window.SCANNER_BRIDGE_TOKEN)
      ? window.SCANNER_BRIDGE_TOKEN : '';
    const wsUrl = token ? `${bridgeUrl}?token=${encodeURIComponent(token)}` : bridgeUrl;
    const ws = new WebSocket(wsUrl);
    wsRef.current = ws;

    ws.onopen = () => {
      if (!mountedRef.current) { ws.close(); return; }
      retriesRef.current = 0;
      setStatus('connected');
      setError(null);
    };

    ws.onmessage = (event) => {
      if (!mountedRef.current) return;
      clearScanTimeout();
      try {
        const parsed = JSON.parse(event.data);
        if (parsed.status === 'success' && parsed.data) {
          // SEC-13: validate MIME type before creating the blob
          const ALLOWED_MIME_TYPES = ['application/pdf', 'image/jpeg', 'image/png', 'image/tiff'];
          if (!ALLOWED_MIME_TYPES.includes(parsed.mimeType)) {
            setError('Server returned an unexpected file type. Scan rejected for security.');
            setStatus('connected');
            return;
          }
          // Convert base64 to Blob (avoids dataURL size limits for large files)
          const byteCharacters = atob(parsed.data);
          const byteNumbers = new Uint8Array(byteCharacters.length);
          for (let i = 0; i < byteCharacters.length; i++) {
            byteNumbers[i] = byteCharacters.charCodeAt(i);
          }
          const blob = new Blob([byteNumbers], { type: parsed.mimeType });
          const blobUrl = URL.createObjectURL(blob);
          // SEC-13: allow only safe filename characters and cap length
          const safeFilename = (parsed.filename || 'scan.pdf')
            .replace(/[^a-zA-Z0-9._-]/g, '_')  // allow only safe characters
            .substring(0, 100);                  // max 100 chars
          const file = new File([blob], safeFilename, { type: parsed.mimeType });
          setResult({ format: parsed.format, mimeType: parsed.mimeType, filename: safeFilename, blobUrl, file });
          setStatus('connected');
          setError(null);
        } else if (parsed.status === 'success' && parsed.scanners) {
          // list response — not stored in result, just passed through (future use)
          setStatus('connected');
        } else if (parsed.status === 'error') {
          setError(parsed.message || 'Unknown error');
          setStatus('connected'); // connection still alive, only scan failed
        }
      } catch {
        setError('Invalid response from scanner bridge');
        setStatus('connected');
      }
    };

    ws.onerror = () => {
      if (!mountedRef.current) return;
      clearScanTimeout();
      setError('Connection error — is Scanner Bridge running?');
    };

    ws.onclose = () => {
      if (!mountedRef.current) return;
      clearScanTimeout();
      setStatus('disconnected');
      wsRef.current = null;
      // Exponential backoff reconnect, up to MAX_RETRIES
      if (retriesRef.current < MAX_RETRIES) {
        const delay = Math.min(1000 * Math.pow(2, retriesRef.current), 30000);
        retriesRef.current += 1;
        reconnectTimerRef.current = setTimeout(connect, delay);
      } else {
        setError('Scanner Bridge unreachable after multiple attempts. Please start it and refresh.');
      }
    };
  }, [bridgeUrl]);

  useEffect(() => {
    mountedRef.current = true;
    connect();
    return () => {
      mountedRef.current = false;
      clearScanTimeout();
      if (reconnectTimerRef.current) clearTimeout(reconnectTimerRef.current);
      if (wsRef.current) {
        const ws = wsRef.current;
        ws.onopen = null;
        ws.onmessage = null;
        ws.onerror = null;
        ws.onclose = null;
        ws.close();
        wsRef.current = null;
      }
    };
  }, [connect]);

  const scan = useCallback((format = 'pdf') => {
    const normalised = format.toLowerCase();
    if (!VALID_FORMATS.includes(normalised)) {
      setError(`Invalid format "${format}". Supported: ${VALID_FORMATS.join(', ')}`);
      return;
    }
    if (!wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) {
      setError('Not connected to Scanner Bridge');
      return;
    }
    // Revoke previous blob URL to free memory
    if (result?.blobUrl) URL.revokeObjectURL(result.blobUrl);
    setResult(null);
    setError(null);
    setStatus('scanning');
    wsRef.current.send(JSON.stringify({ action: 'scan', format: normalised }));
    // Timeout if no response
    scanTimeoutRef.current = setTimeout(() => {
      if (!mountedRef.current) return;
      setError('Scan timed out — no response from scanner bridge after 60 seconds');
      setStatus('connected');
    }, SCAN_TIMEOUT_MS);
  }, [result]);

  const clearResult = useCallback(() => {
    if (result?.blobUrl) URL.revokeObjectURL(result.blobUrl);
    setResult(null);
    setError(null);
  }, [result]);

  return { status, result, error, scan, clearResult };
}
