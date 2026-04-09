package com.scanner.bridge.updater;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "scanner.update")
public class UpdateProperties {

    private boolean enabled = true;
    private String githubRepo = "brainiac992/scanner";
    private long checkIntervalHours = 4;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getGithubRepo() {
        return githubRepo;
    }

    public void setGithubRepo(String githubRepo) {
        this.githubRepo = githubRepo;
    }

    public long getCheckIntervalHours() {
        return checkIntervalHours;
    }

    public void setCheckIntervalHours(long checkIntervalHours) {
        this.checkIntervalHours = checkIntervalHours;
    }
}
