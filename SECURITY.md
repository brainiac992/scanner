# Scanner Bridge — Security Overview

This document describes the security architecture, known risks, and hardening measures applied to Scanner Bridge.

---

## Architecture Security Model

Scanner Bridge is a **localhost-only** Windows Service. It is not exposed to the internet.

```
[Internet]
    ↓ HTTPS
[Web Application — your.agency.gov]
    ↓ WebSocket (ws://localhost:8765)  ← localhost only, never internet-facing
[Scanner Bridge — Windows Service on user's machine]
    ↓ WIA COM API
[Scanner Hardware]
```

**Threat model:** The primary threats are malicious software running on the same machine (same-host attacker) and misconfigured CORS allowing unauthorized web pages to connect.

---

## Security Controls Implemented

### Authentication
- All WebSocket connections require a pre-shared Bearer token
- Token is validated on every handshake — no connection proceeds without it
- Token is configured server-side, rendered into the webapp page at runtime, never stored in client-side source

### Authorization & Access Control
- WebSocket origin strictly validated against configured allowlist (no wildcards)
- Chrome's Private Network Access (PNA) preflight headers enforced
- Windows Service runs as `NT AUTHORITY\LocalService` (minimum required privileges)
- Install directory locked: Administrators = Full Control, LocalService = Read/Execute, Users = no access

### Input Validation
- Format field validated against `ScanFormat` enum (single source of truth)
- Action field validated against strict allowlist `{scan, list}`
- JSON payload: max 1 MB, max nesting depth 10, max string 10 KB
- MIME type validated in React client against allowlist before blob creation
- Filenames sanitized: alphanumeric + `.`, `_`, `-` only, max 100 characters

### Rate Limiting & Resource Protection
- Max 1 concurrent scan per WebSocket session
- Max 4 concurrent scans system-wide
- Disk space checked before each scan (minimum 100 MB required)
- Heap usage checked before queuing each scan (rejects at >90% heap utilisation)
- WebSocket message buffer: 10 MB maximum
- Scan timeout: 60 seconds (React client)

### Information Disclosure Prevention
- Error messages to clients are generic — no stack traces, no file paths, no COM error codes
- Full error details logged server-side only
- Scanner device model names not exposed to clients (returns "Scanner 1", "Scanner 2")

### Secure Communication
- Designed for localhost only — `ws://` is acceptable in this context
- Never expose port 8765 externally
- If remote deployment is ever required, WSS (TLS) is mandatory

### Temp File Security
- Temp file path validated to be within system temp directory (prevents path traversal)
- Files securely deleted immediately after reading
- If deletion fails: file is overwritten with zeros before re-attempting delete

### HTTP Security Headers
All HTTP responses include:
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `X-XSS-Protection: 1; mode=block`
- `Content-Security-Policy: default-src 'none'`
- `Cache-Control: no-store`

### Supply Chain
- JACOB JAR and DLL SHA256 checksums verified at build time
- OWASP Dependency-Check runs during build (`mvn dependency-check:check`)
- Build fails on any dependency with CVSS ≥ 7 (HIGH severity)
- Installer includes `MANIFEST.sha256` for post-install integrity verification

---

## Residual Risks & Accepted Trade-offs

| Risk | Severity | Acceptance Rationale |
|---|---|---|
| Token transmitted as URL query parameter | LOW | Localhost only — not over network; TLS not applicable |
| JACOB is not on Maven Central (manual download) | MEDIUM | SHA256 verification mitigates; JACOB has no viable Maven alternative |
| No mutual TLS between webapp and bridge | LOW | Localhost scope makes network interception infeasible |
| Scanner model exposed in server logs | INFO | Logs are admin-only; acceptable for diagnostic purposes |
| No HSM or TPM for token storage | LOW | Out of scope for a localhost service; OS-level process isolation is sufficient |

---

## Security Maintenance

### Token Rotation
Rotate the shared secret token when:
- A developer with access leaves the team
- You suspect the token has been exposed
- On a regular schedule per your organisation's security policy

To rotate: generate a new token, update `application.yml`, rebuild the installer, redeploy to users, update the webapp configuration.

### Dependency Scanning
Run before every release:
```powershell
mvn dependency-check:check
```

Review any new findings. Update dependencies or add justified suppressions to `dependency-check-suppressions.xml`.

### Incident Response
If Scanner Bridge is suspected to be compromised:
1. Stop the service immediately: `Stop-Service ScannerBridge`
2. Rotate the auth token
3. Review service logs: `C:\Program Files\Scanner Bridge\ScannerBridgeService.out.log`
4. Rebuild and redeploy from a clean build environment
5. Notify your security team

---

## Security Audit History

| Date | Findings | Critical | High | Medium | Low | Status |
|---|---|---|---|---|---|---|
| 2026-03-17 | 18 | 3 | 4 | 8 | 3 | All fixed |

---

## Reporting Vulnerabilities

Report security issues to your IT security team. Do not open public GitHub issues for security vulnerabilities.
