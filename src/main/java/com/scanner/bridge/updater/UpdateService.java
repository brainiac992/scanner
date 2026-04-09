package com.scanner.bridge.updater;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class UpdateService {

    private static final Logger log = LoggerFactory.getLogger(UpdateService.class);
    private static final String CURRENT_VERSION = "1.0.0";

    private final UpdateProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "update-checker");
        t.setDaemon(true);
        return t;
    });

    private volatile String latestVersion;
    private volatile String downloadUrl;
    private volatile boolean updateReady = false;

    public UpdateService(UpdateProperties props) {
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!props.isEnabled()) {
            log.info("Auto-update is disabled");
            return;
        }
        log.info("Auto-update enabled — checking {} every {} hours",
                props.getGithubRepo(), props.getCheckIntervalHours());

        // Check after 30 seconds, then every N hours
        scheduler.scheduleAtFixedRate(this::checkAndDownload,
                30, props.getCheckIntervalHours() * 3600, TimeUnit.SECONDS);
    }

    public String getCurrentVersion() {
        return CURRENT_VERSION;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public boolean isUpdateReady() {
        return updateReady;
    }

    /**
     * Checks GitHub for a new release and downloads it if available.
     */
    private void checkAndDownload() {
        try {
            log.info("Checking for updates...");
            JsonNode release = fetchLatestRelease();
            if (release == null) return;

            String tagName = release.get("tag_name").asText();
            // Strip leading 'v' if present: "v1.1.0" → "1.1.0"
            latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;

            if (!isNewer(latestVersion, CURRENT_VERSION)) {
                log.info("Already on latest version ({})", CURRENT_VERSION);
                return;
            }

            log.info("New version available: {} (current: {})", latestVersion, CURRENT_VERSION);

            // Find the ZIP asset
            JsonNode assets = release.get("assets");
            if (assets == null || !assets.isArray()) {
                log.warn("No assets found in release {}", latestVersion);
                return;
            }

            String assetUrl = null;
            for (JsonNode asset : assets) {
                String name = asset.get("name").asText();
                if (name.toLowerCase().endsWith(".zip")) {
                    assetUrl = asset.get("browser_download_url").asText();
                    break;
                }
            }

            if (assetUrl == null) {
                log.warn("No ZIP asset found in release {}", latestVersion);
                return;
            }

            downloadUrl = assetUrl;
            Path updateDir = getUpdateStagingDir();
            Path zipFile = updateDir.resolve("update.zip");

            // Download
            log.info("Downloading update from {}", assetUrl);
            downloadFile(assetUrl, zipFile);
            log.info("Downloaded update ({} bytes)", Files.size(zipFile));

            // Extract
            Path extractDir = updateDir.resolve("extracted");
            if (Files.exists(extractDir)) {
                deleteDirectory(extractDir);
            }
            Files.createDirectories(extractDir);
            unzip(zipFile, extractDir);
            log.info("Update extracted to {}", extractDir);

            // Write updater script
            writeUpdaterScript(updateDir, extractDir);

            updateReady = true;
            log.info("Update {} is ready — will apply on next idle window", latestVersion);

            // Apply immediately (service will restart)
            applyUpdate();

        } catch (Exception e) {
            log.error("Update check failed: {}", e.getMessage());
        }
    }

    /**
     * Applies the staged update by running the updater script.
     * The script stops the service, copies new files, and restarts.
     */
    public void applyUpdate() {
        if (!updateReady) {
            log.info("No update ready to apply");
            return;
        }

        try {
            Path updateDir = getUpdateStagingDir();
            Path script = updateDir.resolve("apply-update.bat");

            if (!Files.exists(script)) {
                log.error("Updater script not found at {}", script);
                return;
            }

            log.info("Applying update {}...", latestVersion);

            // Run the updater script as a detached process.
            // It will stop this service, copy files, and restart.
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", "start", "/b",
                    script.toAbsolutePath().toString());
            pb.directory(updateDir.toFile());
            pb.redirectErrorStream(true);
            pb.start();

            log.info("Updater launched — service will restart shortly");

        } catch (Exception e) {
            log.error("Failed to apply update: {}", e.getMessage());
        }
    }

    private JsonNode fetchLatestRelease() throws Exception {
        String url = "https://api.github.com/repos/" + props.getGithubRepo() + "/releases/latest";
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setRequestProperty("User-Agent", "ScannerBridge/" + CURRENT_VERSION);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);

        int status = conn.getResponseCode();
        if (status == 404) {
            log.info("No releases found on {}", props.getGithubRepo());
            return null;
        }
        if (status != 200) {
            log.warn("GitHub API returned {}", status);
            return null;
        }

        try (InputStream is = conn.getInputStream()) {
            return mapper.readTree(is);
        }
    }

    private void downloadFile(String url, Path dest) throws Exception {
        Files.createDirectories(dest.getParent());
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestProperty("User-Agent", "ScannerBridge/" + CURRENT_VERSION);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(120_000);
        conn.setInstanceFollowRedirects(true);

        try (InputStream is = conn.getInputStream();
             OutputStream os = Files.newOutputStream(dest)) {
            is.transferTo(os);
        }
    }

    private void unzip(Path zipFile, Path destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = destDir.resolve(entry.getName()).normalize();
                // Prevent zip slip
                if (!target.startsWith(destDir)) {
                    throw new IOException("Zip entry outside target dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream os = Files.newOutputStream(target)) {
                        zis.transferTo(os);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private void writeUpdaterScript(Path updateDir, Path extractDir) throws IOException {
        // Resolve the app install directory (where Scanner Bridge.exe lives)
        Path appDir = getAppInstallDir();

        String script = "@echo off\r\n"
                + ":: Auto-update script — generated by Scanner Bridge\r\n"
                + "timeout /t 3 /nobreak >nul\r\n"
                + "\r\n"
                + ":: Stop the service\r\n"
                + "net stop ScannerBridge >nul 2>&1\r\n"
                + "timeout /t 2 /nobreak >nul\r\n"
                + "\r\n"
                + ":: Copy new files over existing installation\r\n"
                + "xcopy /s /y /q \"" + extractDir.toAbsolutePath() + "\\*\" \"" + appDir.toAbsolutePath() + "\\\"\r\n"
                + "\r\n"
                + ":: Restart the service\r\n"
                + "net start ScannerBridge >nul 2>&1\r\n"
                + "\r\n"
                + ":: Clean up staging directory\r\n"
                + "timeout /t 5 /nobreak >nul\r\n"
                + "rd /s /q \"" + updateDir.toAbsolutePath() + "\" >nul 2>&1\r\n";

        Files.writeString(updateDir.resolve("apply-update.bat"), script);
    }

    /**
     * Returns the installation directory of the app.
     * In a jpackage deployment: the directory containing "Scanner Bridge.exe"
     * In dev mode: the project root
     */
    private Path getAppInstallDir() {
        // Check for jpackage layout: the JAR is inside app/ under the install dir
        String classPath = System.getProperty("java.class.path", "");
        String firstEntry = classPath.split(File.pathSeparator)[0];
        File cpFile = new File(firstEntry);

        if (cpFile.isFile() && cpFile.getName().endsWith(".jar")) {
            // target/scanner-bridge.jar → target (dev) or app/ → install dir (jpackage)
            Path parent = cpFile.toPath().getParent();
            if ("app".equals(parent.getFileName().toString())) {
                return parent.getParent(); // jpackage install dir
            }
            return parent;
        }
        // IDE: target/classes → project root
        return cpFile.toPath().getParent().getParent();
    }

    private Path getUpdateStagingDir() {
        return Paths.get(System.getProperty("java.io.tmpdir"), "scanner-bridge-update");
    }

    /**
     * Compares two semver strings. Returns true if newVer > oldVer.
     */
    static boolean isNewer(String newVer, String oldVer) {
        String[] newParts = newVer.split("\\.");
        String[] oldParts = oldVer.split("\\.");
        for (int i = 0; i < Math.max(newParts.length, oldParts.length); i++) {
            int n = i < newParts.length ? Integer.parseInt(newParts[i]) : 0;
            int o = i < oldParts.length ? Integer.parseInt(oldParts[i]) : 0;
            if (n > o) return true;
            if (n < o) return false;
        }
        return false;
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir)
                .sorted((a, b) -> b.compareTo(a)) // deepest first
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
    }
}
