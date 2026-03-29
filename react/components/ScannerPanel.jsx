import { useState } from 'react';
import { useScanner } from '../hooks/useScanner';
import { useCallback } from 'react';

const STATUS_CONFIG = {
  connected:    { label: 'Connected',    color: '#2e7d32' },
  connecting:   { label: 'Connecting…',  color: '#b8860b' },
  disconnected: { label: 'Disconnected', color: '#c62828' },
  scanning:     { label: 'Scanning…',    color: '#1565c0' },
  error:        { label: 'Error',        color: '#c62828' },
};

const FORMATS = ['PDF', 'JPEG', 'PNG', 'TIFF'];

const IMAGE_FORMATS = new Set(['jpeg', 'jpg', 'png']);

const styles = {
  panel: {
    fontFamily: '"Segoe UI", Arial, sans-serif',
    fontSize: '14px',
    color: '#1a1a1a',
    backgroundColor: '#f5f5f5',
    border: '1px solid #bdbdbd',
    borderRadius: '4px',
    padding: '24px',
    maxWidth: '520px',
    margin: '0 auto',
  },
  heading: {
    fontSize: '18px',
    fontWeight: '600',
    marginBottom: '20px',
    marginTop: '0',
    borderBottom: '2px solid #1565c0',
    paddingBottom: '10px',
    color: '#0d2b5e',
  },
  section: {
    marginBottom: '18px',
  },
  label: {
    display: 'block',
    fontWeight: '600',
    marginBottom: '8px',
    color: '#333',
  },
  statusBadge: {
    display: 'inline-flex',
    alignItems: 'center',
    gap: '8px',
    padding: '4px 10px',
    backgroundColor: '#fff',
    border: '1px solid #bdbdbd',
    borderRadius: '3px',
    fontSize: '13px',
  },
  dot: (color) => ({
    width: '9px',
    height: '9px',
    borderRadius: '50%',
    backgroundColor: color,
    flexShrink: 0,
  }),
  formatGroup: {
    display: 'flex',
    gap: '8px',
    flexWrap: 'wrap',
  },
  formatButton: (active) => ({
    padding: '6px 16px',
    border: active ? '2px solid #1565c0' : '1px solid #bdbdbd',
    borderRadius: '3px',
    backgroundColor: active ? '#e3eaf6' : '#fff',
    color: active ? '#0d2b5e' : '#333',
    fontWeight: active ? '600' : '400',
    cursor: 'pointer',
    fontSize: '13px',
    letterSpacing: '0.02em',
  }),
  scanButton: (disabled) => ({
    display: 'inline-flex',
    alignItems: 'center',
    gap: '8px',
    padding: '8px 22px',
    backgroundColor: disabled ? '#90a4ae' : '#1565c0',
    color: '#fff',
    border: 'none',
    borderRadius: '3px',
    fontWeight: '600',
    fontSize: '14px',
    cursor: disabled ? 'not-allowed' : 'pointer',
    letterSpacing: '0.03em',
  }),
  resultBox: {
    backgroundColor: '#fff',
    border: '1px solid #bdbdbd',
    borderRadius: '4px',
    padding: '16px',
  },
  previewImage: {
    display: 'block',
    maxWidth: '100%',
    maxHeight: '320px',
    border: '1px solid #e0e0e0',
    marginBottom: '12px',
  },
  pdfNotice: {
    display: 'flex',
    alignItems: 'center',
    gap: '10px',
    padding: '12px',
    backgroundColor: '#f0f4fa',
    border: '1px solid #c5d2e8',
    borderRadius: '3px',
    marginBottom: '12px',
    fontSize: '13px',
    color: '#0d2b5e',
  },
  pdfIcon: {
    fontSize: '22px',
    lineHeight: '1',
  },
  actionRow: {
    display: 'flex',
    gap: '10px',
    flexWrap: 'wrap',
    marginTop: '12px',
  },
  uploadButton: (disabled) => ({
    display: 'inline-flex',
    alignItems: 'center',
    gap: '6px',
    padding: '7px 16px',
    backgroundColor: disabled ? '#90a4ae' : '#2e7d32',
    color: '#fff',
    borderRadius: '3px',
    fontWeight: '600',
    fontSize: '13px',
    border: 'none',
    cursor: disabled ? 'not-allowed' : 'pointer',
  }),
  scanAgainButton: {
    padding: '7px 16px',
    backgroundColor: '#fff',
    color: '#1565c0',
    border: '1px solid #1565c0',
    borderRadius: '3px',
    fontWeight: '600',
    fontSize: '13px',
    cursor: 'pointer',
  },
  errorBox: {
    backgroundColor: '#ffebee',
    border: '1px solid #ef9a9a',
    borderRadius: '3px',
    padding: '10px 14px',
    color: '#b71c1c',
    fontSize: '13px',
  },
  filename: {
    fontSize: '12px',
    color: '#555',
    marginBottom: '10px',
    wordBreak: 'break-all',
  },
  spinner: {
    display: 'inline-block',
    width: '13px',
    height: '13px',
    border: '2px solid rgba(255,255,255,0.4)',
    borderTopColor: '#fff',
    borderRadius: '50%',
    animation: 'spin 0.7s linear infinite',
  },
};

function Spinner() {
  return (
    <>
      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
      <span style={styles.spinner} aria-hidden="true" />
    </>
  );
}

export default function ScannerPanel({ onUpload }) {
  const [selectedFormat, setSelectedFormat] = useState('PDF');
  const [uploading, setUploading] = useState(false);
  const { status, result, error, scan, clearResult } = useScanner();

  const isConnected = status === 'connected';
  const isScanning  = status === 'scanning';
  const scanDisabled = !isConnected || isScanning;

  const handleScan = () => {
    scan(selectedFormat.toLowerCase());
  };

  const handleUpload = useCallback(async () => {
    if (!result?.file || !onUpload) return;
    setUploading(true);
    try {
      await onUpload(result.file);
      clearResult();
    } finally {
      setUploading(false);
    }
  }, [result, onUpload, clearResult]);

  const statusCfg = STATUS_CONFIG[status] ?? STATUS_CONFIG.disconnected;
  const isImage = result && IMAGE_FORMATS.has(result.format.toLowerCase());

  return (
    <div style={styles.panel} role="region" aria-label="Scanner Panel">
      <h2 style={styles.heading}>Document Scanner</h2>

      {/* Connection status */}
      <div style={styles.section}>
        <span style={styles.label}>Connection Status</span>
        <div style={styles.statusBadge} aria-live="polite">
          <span style={styles.dot(statusCfg.color)} />
          <span style={{ color: statusCfg.color, fontWeight: '600' }}>
            {statusCfg.label}
          </span>
        </div>
      </div>

      {/* Format selector */}
      {!result && (
        <div style={styles.section}>
          <span style={styles.label} id="format-label">Output Format</span>
          <div style={styles.formatGroup} role="group" aria-labelledby="format-label">
            {FORMATS.map((fmt) => (
              <button
                key={fmt}
                style={styles.formatButton(selectedFormat === fmt)}
                onClick={() => setSelectedFormat(fmt)}
                aria-pressed={selectedFormat === fmt}
                aria-label={fmt + ' format'}
                aria-disabled={isScanning}
                disabled={isScanning}
              >
                {fmt}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Scan button */}
      {!result && (
        <div style={styles.section}>
          <button
            style={styles.scanButton(scanDisabled)}
            onClick={handleScan}
            disabled={scanDisabled}
            aria-busy={isScanning}
          >
            {isScanning && <Spinner />}
            {isScanning ? 'Scanning…' : 'Scan Document'}
          </button>
        </div>
      )}

      {/* Result area */}
      {result && (
        <div style={{ ...styles.section, ...styles.resultBox }}>
          <div style={styles.filename}>
            <strong>File:</strong> {result.filename}
          </div>

          {isImage ? (
            <img
              src={result.blobUrl}
              alt={`Scanned document — ${result.filename}`}
              style={styles.previewImage}
            />
          ) : (
            <div style={styles.pdfNotice} role="status">
              <span style={styles.pdfIcon} aria-hidden="true">&#128196;</span>
              <span>
                <strong>PDF ready.</strong> Click "Attach Document" to upload.
              </span>
            </div>
          )}

          <div style={styles.actionRow}>
            <button
              style={styles.uploadButton(uploading || !onUpload)}
              onClick={handleUpload}
              disabled={uploading || !onUpload}
              aria-busy={uploading}
              aria-label={`Upload ${result.filename}`}
            >
              {uploading && <Spinner />}
              {uploading ? 'Uploading…' : 'Attach Document'}
            </button>
            <button
              style={styles.scanAgainButton}
              onClick={clearResult}
              disabled={uploading}
              aria-label="Clear result and scan again"
            >
              Scan Again
            </button>
          </div>
        </div>
      )}

      {/* Error message */}
      {error && (
        <div style={styles.section}>
          <div style={styles.errorBox} role="alert">
            <strong>Error:</strong> {error}
          </div>
        </div>
      )}
    </div>
  );
}
