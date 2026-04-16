package com.scanner.bridge.scanner;

import com.jacob.activeX.ActiveXComponent;
import com.jacob.com.ComFailException;
import com.jacob.com.ComThread;
import com.jacob.com.Dispatch;
import com.jacob.com.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Windows Image Acquisition (WIA) scanner integration using the JACOB library (Java COM Bridge).
 *
 * <p><b>Native DLL requirement:</b> JACOB requires {@code jacob-1.21-x64.dll} (or the x86
 * variant for 32-bit JVMs) to be available on {@code java.library.path} at runtime.
 * The recommended approach is to place the DLL next to the application JAR and launch with:
 * <pre>java -Djava.library.path=. -jar scanner-bridge.jar</pre>
 * Alternatively, copy the DLL to a directory already on the system PATH (e.g.
 * {@code C:\Windows\System32}).  The JACOB JAR itself must also be on the classpath.</p>
 *
 * <p><b>BMP transfer format:</b> All raw scans are performed using the BMP format GUID
 * {@code {B96B3CAB-0728-11D3-9D7B-0000F81EF32E}}.  Conversion to other formats (JPEG, PNG,
 * TIFF, etc.) is delegated to {@code FileConverter} after this class returns the raw bytes.</p>
 *
 * <p>Not loaded when the {@code test} Spring profile is active; use {@link MockScannerImpl}
 * instead.</p>
 */
@Service
@Profile("!test")
public class WiaScanner implements ScannerService {

    private static final Logger log = LoggerFactory.getLogger(WiaScanner.class);

    /** Serialises all COM calls to prevent concurrent access to the single-threaded COM apartment. */
    private static final Object COM_LOCK = new Object();

    /**
     * WIA BMP transfer format GUID.
     * Using BMP as the raw transfer format; downstream FileConverter handles conversion.
     */
    private static final String BMP_FORMAT_GUID = "{B96B3CAB-0728-11D3-9D7B-0000F81EF32E}";

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Scans one or more pages from the first available WIA scanner device and returns the raw
     * image bytes in BMP format, one element per page.
     *
     * <p>If the scanner reports ADF (Automatic Document Feeder) capability via WIA property
     * 3086 ({@code WIA_DPS_DOCUMENT_HANDLING_CAPABILITIES}), the feeder is selected and all
     * pages in the feeder are consumed until the "Paper Empty" COM error is raised.  Otherwise
     * a single flatbed scan is performed and returned as a one-element list.</p>
     *
     * @return list of raw BMP image bytes, one entry per scanned page (never null, never empty)
     * @throws Exception if no scanner is found, if the COM call fails, or if I/O fails
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<byte[]> scan(int maxPages) throws Exception {
        log.info("Starting WIA scan (maxPages={})", maxPages);

        synchronized (COM_LOCK) {
            // SEC-14: check available disk space before scanning (scanned BMP can be 10-50 MB)
            File tempDir = new File(System.getProperty("java.io.tmpdir"));
            long freeSpaceBytes = tempDir.getUsableSpace();
            long minimumRequired = 100L * 1024 * 1024; // 100 MB
            if (freeSpaceBytes < minimumRequired) {
                throw new IllegalStateException("Insufficient disk space for scan. Available: "
                        + (freeSpaceBytes / 1024 / 1024) + " MB, required: 100 MB");
            }

            // WIA COM automation requires an STA (Single-Threaded Apartment) thread.
            // Running on an MTA thread (e.g. from CompletableFuture/ForkJoinPool) causes
            // the WIA Connect/Transfer calls to hang indefinitely.
            final List<byte[]>[] resultHolder = new List[1];
            final Exception[] errorHolder = new Exception[1];

            Thread staThread = new Thread(() -> {
                ComThread.InitSTA();
                try {
                    ActiveXComponent wiaManager = new ActiveXComponent("WIA.DeviceManager");
                    log.debug("WIA DeviceManager initialised");

                    // Enumerate connected scanner devices
                    Dispatch devInfos = wiaManager.getPropertyAsComponent("DeviceInfos");
                    int count = Dispatch.get(devInfos, "Count").getInt();
                    log.debug("WIA device count: {}", count);

                    if (count == 0) {
                        throw new IllegalStateException("No scanner device found");
                    }

                    // Try each WIA device in order until one connects successfully.
                    // WIA_DIT_SCANNER = 1; skip cameras (2) and video (3).
                    // Devices can appear in the list but fail Connect() if offline/sleeping.
                    Dispatch device = null;
                    for (int i = 1; i <= count; i++) {
                        Dispatch candidate = Dispatch.call(devInfos, "Item", i).toDispatch();
                        int devType = Dispatch.get(candidate, "Type").getInt();
                        if (devType != 1) {
                            log.debug("Skipping WIA device {} — type {} is not a scanner", i, devType);
                            continue;
                        }
                        try {
                            log.debug("Attempting to connect to WIA device {}/{}", i, count);
                            device = Dispatch.call(candidate, "Connect").toDispatch();
                            log.info("Connected to WIA scanner device {}/{}", i, count);
                            break;
                        } catch (Exception connectEx) {
                            log.warn("WIA device {}/{} not available: {}", i, count, connectEx.getMessage());
                        }
                    }
                    if (device == null) {
                        throw new IllegalStateException(
                                "No online scanner device found among " + count + " WIA device(s). " +
                                "Ensure the scanner is powered on and not in use by another application.");
                    }

                    // ── Stage 1: Enumerate scan items ────────────────────────────────────────
                    // Some scanners (mostly older or non-HP models) expose ADF as a separate
                    // named WIA Item ("Feeder", "ADF", "Automatic Document Feeder") rather than
                    // via device properties.  Select that item when found; fall back to item 1.
                    Dispatch items = Dispatch.get(device, "Items").toDispatch();
                    int itemCount = Dispatch.get(items, "Count").getInt();
                    log.info("Device has {} scan item(s)", itemCount);

                    Dispatch scanItem = Dispatch.call(items, "Item", 1).toDispatch();
                    boolean adfConfigured = false;

                    for (int i = 1; i <= itemCount; i++) {
                        Dispatch candidate = Dispatch.call(items, "Item", i).toDispatch();
                        String itemName = "";
                        try {
                            itemName = Dispatch.get(candidate, "Name").getString();
                        } catch (Exception e) { /* Name property may not exist */ }
                        log.info("Scan item {}/{}: '{}'", i, itemCount, itemName);
                        String lower = itemName.toLowerCase();
                        if (lower.contains("feed") || lower.contains("adf")
                                || lower.contains("automatic") || lower.contains("document")) {
                            scanItem = candidate;
                            adfConfigured = true;
                            log.info("Stage 1 – selected ADF item by name: '{}'", itemName);
                        }
                    }

                    // ── Stage 2: Standard WIA property detection ──────────────────────────────
                    // WIA_DPS_DOCUMENT_HANDLING_CAPABILITIES (3086) — bit 0x01 = FEED means
                    // the device has an ADF.  If detected, configure:
                    //   3088 (WIA_DPS_DOCUMENT_HANDLING_SELECT) = 1  (FEEDER)
                    //   3096 (WIA_DPS_PAGES)                    = 0  (ALL_PAGES)
                    // Try on the device-level properties first, then on the scan item.
                    if (!adfConfigured) {
                        for (String source : new String[]{"device", "item"}) {
                            try {
                                Dispatch props = "device".equals(source)
                                        ? Dispatch.get(device, "Properties").toDispatch()
                                        : Dispatch.get(scanItem, "Properties").toDispatch();

                                // Read capabilities first (optional — not all drivers expose it)
                                try {
                                    Dispatch capProp = Dispatch.call(props, "Item", 3086).toDispatch();
                                    int caps = Dispatch.get(capProp, "Value").getInt();
                                    if ((caps & 0x01) == 0) {
                                        log.info("Stage 2 ({}) – 3086 caps=0x{} → no FEED bit; flatbed only",
                                                source, Integer.toHexString(caps));
                                        break; // This is definitely a flatbed scanner; skip Stage 2
                                    }
                                    log.info("Stage 2 ({}) – 3086 caps=0x{} → FEED bit set", source,
                                            Integer.toHexString(caps));
                                } catch (Exception capEx) {
                                    log.debug("Stage 2 ({}) – 3086 not readable ({}); will try setting 3088 directly",
                                            source, capEx.getMessage());
                                }

                                // Set feeder mode
                                Dispatch selectProp = Dispatch.call(props, "Item", 3088).toDispatch();
                                Dispatch.put(selectProp, "Value", new Variant(1)); // FEEDER
                                Dispatch pagesProp = Dispatch.call(props, "Item", 3096).toDispatch();
                                Dispatch.put(pagesProp, "Value", new Variant(0)); // ALL_PAGES
                                adfConfigured = true;
                                log.info("Stage 2 – ADF configured via {} properties (3088=FEEDER, 3096=ALL_PAGES)",
                                        source);
                                break;
                            } catch (Exception e) {
                                log.info("Stage 2 ({}) – property config failed: {}", source, e.getMessage());
                            }
                        }
                    }

                    // ── Stage 3: Unconditional loop (universal fallback) ───────────────────────
                    // If neither Stage 1 nor Stage 2 could configure the feeder, we still loop
                    // unconditionally.  Scanners that physically have an ADF but don't expose
                    // WIA properties (e.g. many HP, Canon, Fujitsu models) will continue feeding
                    // pages on successive Transfer calls and raise a COM "Paper Empty" error only
                    // when the feeder is exhausted.  For flatbed scanners, the second Transfer
                    // call fails immediately, so we always get exactly one page — no extra logic
                    // needed.  This loop therefore handles all three cases generically.

                    List<byte[]> pages = new ArrayList<>();

                    log.info("Starting scan loop [adfConfigured={}] — will stop on COM error after ≥1 page",
                            adfConfigured);
                    while (true) {
                        try {
                            byte[] pageBytes = transferItem(scanItem);
                            pages.add(pageBytes);
                            log.info("Page {} scanned ({} bytes)", pages.size(), pageBytes.length);
                            if (maxPages > 0 && pages.size() >= maxPages) {
                                log.info("Reached maxPages limit ({}), stopping ADF scan", maxPages);
                                break;
                            }
                        } catch (ComFailException cfe) {
                            if (pages.isEmpty()) {
                                // First page failed — retry once after 2 s to recover from transient states
                                log.warn("First page Transfer failed ({}), retrying in 2 s...", cfe.getMessage());
                                try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                                byte[] retryBytes = transferItem(scanItem); // throws if still failing
                                pages.add(retryBytes);
                                log.info("Page 1 scanned on retry ({} bytes)", retryBytes.length);
                                continue;
                            }
                            log.info("Scan loop ended after {} page(s): {}", pages.size(), cfe.getMessage());
                            break;
                        }
                    }

                    resultHolder[0] = pages;

                } catch (Exception ex) {
                    errorHolder[0] = ex;
                } finally {
                    ComThread.Release();
                    log.debug("COM thread released");
                }
            }, "wia-sta-scan");

            staThread.start();
            // Option 3: cap the WIA thread at 55 s — slightly under the frontend's 60 s
            // scan timeout. If WIA hangs (stale driver / sleeping scanner), this unblocks
            // the scan executor thread so the bridge can send an error instead of hanging.
            staThread.join(55_000);
            if (staThread.isAlive()) {
                staThread.interrupt();
                log.error("WIA scan thread did not complete within 55 s — interrupted");
                throw new IllegalStateException(
                        "Scanner did not respond within the allowed time. " +
                        "Ensure the scanner is powered on and not in use by another application.");
            }

            if (errorHolder[0] != null) {
                throw errorHolder[0];
            }
            return resultHolder[0];
        }
    }

    /**
     * Transfers a single page from a WIA scan item, saves it to a temp BMP file, reads the
     * bytes, and always deletes the temp file in a finally block.
     *
     * @param item WIA scan item Dispatch object
     * @return raw BMP bytes for the transferred page
     * @throws Exception on COM transfer failure or I/O error
     */
    private byte[] transferItem(Dispatch item) throws Exception {
        Dispatch imageFile = Dispatch.call(item, "Transfer", BMP_FORMAT_GUID).toDispatch();
        log.debug("WIA Transfer complete; saving to temp file");

        // SEC-08: create temp file inside system temp dir, then immediately delete so
        // SaveFile (which requires a non-existent destination path) can write to it
        Path tempFile = Files.createTempFile("wia_scan_", ".bmp");
        Files.delete(tempFile);
        String tempPath = tempFile.toAbsolutePath().toString();

        try {
            Dispatch.call(imageFile, "SaveFile", tempPath);
            log.debug("WIA image saved: {}, size: {} bytes", tempPath, Files.size(tempFile));
            byte[] bytes = Files.readAllBytes(tempFile);
            log.debug("Read {} bytes from WIA image file", bytes.length);
            return bytes;
        } finally {
            // SEC-08: always delete temp file — contains unencrypted scan data
            try {
                Files.deleteIfExists(tempFile);
                log.debug("Deleted WIA temp file: {}", tempPath);
            } catch (IOException deleteEx) {
                log.warn("Could not delete WIA temp file: {}", tempPath);
            }
        }
    }

    /**
     * Returns the display names of all WIA scanner devices currently visible to the system.
     * Useful for populating a device-selection UI.
     *
     * @return list of device names; empty if no scanners are connected
     */
    @Override
    public List<String> listScanners() {
        log.info("Listing available WIA scanner devices");

        List<String> names = new ArrayList<>();

        synchronized (COM_LOCK) {
            Thread staThread = new Thread(() -> {
                ComThread.InitSTA();
                try {
                    ActiveXComponent wiaManager = new ActiveXComponent("WIA.DeviceManager");
                    Dispatch devInfos = wiaManager.getPropertyAsComponent("DeviceInfos");
                    int count = Dispatch.get(devInfos, "Count").getInt();
                    log.debug("Found {} WIA device(s)", count);

                    for (int i = 1; i <= count; i++) {
                        Dispatch devInfo = Dispatch.call(devInfos, "Item", i).toDispatch();

                        Dispatch properties = Dispatch.get(devInfo, "Properties").toDispatch();
                        Variant nameVariant = Dispatch.call(properties, "Item", "Name");
                        String realName = nameVariant.getString();

                        log.info("Discovered scanner device: {}", realName);
                        names.add("Scanner " + (names.size() + 1));
                    }

                } catch (Exception ex) {
                    log.error("Error while enumerating WIA devices: {}", ex.getMessage(), ex);
                } finally {
                    ComThread.Release();
                    log.debug("COM thread released after device enumeration");
                }
            }, "wia-sta-list");

            staThread.start();
            try {
                staThread.join();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while listing scanners", ex);
            }
        }

        return names;
    }
}
