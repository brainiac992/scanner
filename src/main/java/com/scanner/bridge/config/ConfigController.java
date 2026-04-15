package com.scanner.bridge.config;

import com.scanner.bridge.updater.UpdateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Exposes bridge configuration to the frontend at {@code GET /config}.
 *
 * <p>This endpoint is intentionally unauthenticated — the frontend needs
 * the token before it can authenticate. It is protected by CORS (only
 * allowed origins can read the response) and by the fact that the bridge
 * only listens on localhost, making it unreachable from external networks.
 *
 * <p>Response: {@code { "token": "...", "version": "1.0.0" }}
 */
@RestController
public class ConfigController {

    private final ScannerProperties scannerProperties;
    private final UpdateService updateService;

    public ConfigController(ScannerProperties scannerProperties, UpdateService updateService) {
        this.scannerProperties = scannerProperties;
        this.updateService = updateService;
    }

    @GetMapping("/config")
    public Map<String, String> getConfig() {
        return Map.of(
                "token", scannerProperties.getAuth().getToken(),
                "version", updateService.getCurrentVersion()
        );
    }
}
