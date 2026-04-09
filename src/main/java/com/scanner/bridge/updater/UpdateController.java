package com.scanner.bridge.updater;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/update")
public class UpdateController {

    private final UpdateService updateService;

    public UpdateController(UpdateService updateService) {
        this.updateService = updateService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "currentVersion", updateService.getCurrentVersion(),
                "latestVersion", updateService.getLatestVersion() != null
                        ? updateService.getLatestVersion() : updateService.getCurrentVersion(),
                "updateReady", updateService.isUpdateReady()
        );
    }
}
