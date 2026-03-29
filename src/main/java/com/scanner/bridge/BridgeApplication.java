package com.scanner.bridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.scanner.bridge.config.ScannerProperties;

@SpringBootApplication
@EnableConfigurationProperties(ScannerProperties.class)
public class BridgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(BridgeApplication.class, args);
    }
}
