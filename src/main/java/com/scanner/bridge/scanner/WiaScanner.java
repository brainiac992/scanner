package com.scanner.bridge.scanner;

import com.jacob.activeX.ActiveXComponent;
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
     * Scans a single page from the first available WIA scanner device and returns the raw
     * image bytes in BMP format.
     *
     * <p>The method initialises a COM Multi-Threaded Apartment (MTA) for the calling thread,
     * performs the scan, writes the result to a temporary file, reads the bytes, removes the
     * temp file, and releases the COM apartment — all within a single try/finally block.</p>
     *
     * @return raw BMP image bytes
     * @throws Exception if no scanner is found, if the COM call fails, or if I/O fails
     */
    @Override
    public byte[] scan() throws Exception {
        log.info("Starting WIA scan");

        synchronized (COM_LOCK) {
            // SEC-14: check available disk space before scanning (scanned BMP can be 10-50 MB)
            File tempDir = new File(System.getProperty("java.io.tmpdir"));
            long freeSpaceBytes = tempDir.getUsableSpace();
            long minimumRequired = 100L * 1024 * 1024; // 100 MB
            if (freeSpaceBytes < minimumRequired) {
                throw new IllegalStateException("Insufficient disk space for scan. Available: "
                        + (freeSpaceBytes / 1024 / 1024) + " MB, required: 100 MB");
            }

            ComThread.InitMTA();
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

                // Connect to the first available device (WIA collection indices are 1-based)
                Dispatch devInfo = Dispatch.call(devInfos, "Item", 1).toDispatch();
                log.debug("Connecting to scanner device");
                Dispatch device = Dispatch.call(devInfo, "Connect").toDispatch();

                // Retrieve the scan items collection and pick the first item
                Dispatch items = Dispatch.get(device, "Items").toDispatch();
                int itemCount = Dispatch.get(items, "Count").getInt();
                if (itemCount == 0) {
                    throw new IllegalStateException("No scan items available on the connected device");
                }
                Dispatch item = Dispatch.call(items, "Item", 1).toDispatch();
                log.debug("Acquired scan item; initiating transfer in BMP format");

                // Transfer the image — WIA writes the result to an IWiaImageFile COM object
                Dispatch imageFile = Dispatch.call(item, "Transfer", BMP_FORMAT_GUID).toDispatch();

                // Retrieve the file path that WIA wrote the image to
                String fullName = Dispatch.get(imageFile, "FullName").getString();
                log.info("WIA transfer complete; image written to: {}", fullName);

                // SEC-08: validate the path is within the system temp directory to prevent path traversal
                Path tempFile = Path.of(fullName);
                Path systemTemp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
                Path realTempFile = tempFile.toRealPath();
                if (!realTempFile.startsWith(systemTemp)) {
                    throw new SecurityException(
                            "WIA returned a file path outside of the temp directory: " + fullName);
                }
                byte[] imageBytes = Files.readAllBytes(realTempFile);
                log.debug("Read {} bytes from WIA image file", imageBytes.length);

                // SEC-08: critical — temp file must be deleted (contains unencrypted scanned document data)
                try {
                    Files.delete(realTempFile);
                    log.debug("Deleted WIA temp file: {}", fullName);
                } catch (IOException deleteEx) {
                    // Overwrite with zeros before throwing so data is not left on disk
                    Files.write(realTempFile, new byte[(int) Files.size(realTempFile)]);
                    Files.delete(realTempFile);
                }

                return imageBytes;

            } finally {
                ComThread.Release();
                log.debug("COM thread released");
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
            ComThread.InitMTA();
            try {
                ActiveXComponent wiaManager = new ActiveXComponent("WIA.DeviceManager");
                Dispatch devInfos = wiaManager.getPropertyAsComponent("DeviceInfos");
                int count = Dispatch.get(devInfos, "Count").getInt();
                log.debug("Found {} WIA device(s)", count);

                for (int i = 1; i <= count; i++) {
                    Dispatch devInfo = Dispatch.call(devInfos, "Item", i).toDispatch();

                    // WIA device properties are exposed through the Properties collection.
                    // Property ID 7 (WIA_DIP_DEV_NAME) holds the human-readable device name.
                    Dispatch properties = Dispatch.get(devInfo, "Properties").toDispatch();
                    Variant nameVariant = Dispatch.call(properties, "Item", "Name");
                    String realName = nameVariant.getString();

                    // SEC-10: log real device name server-side only — do not expose hardware model to callers
                    log.info("Discovered scanner device: {}", realName);
                    names.add("Scanner " + (names.size() + 1));
                }

            } catch (Exception ex) {
                // Return whatever names were collected before the error; log the failure
                log.error("Error while enumerating WIA devices: {}", ex.getMessage(), ex);
            } finally {
                ComThread.Release();
                log.debug("COM thread released after device enumeration");
            }
        }

        return names;
    }
}
