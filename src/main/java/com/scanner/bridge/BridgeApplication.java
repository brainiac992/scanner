package com.scanner.bridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.scanner.bridge.config.ScannerProperties;

import java.io.File;
import java.nio.file.Path;

@SpringBootApplication
@EnableConfigurationProperties(ScannerProperties.class)
public class BridgeApplication {
    public static void main(String[] args) {
        configureJacobLibraryPath();
        SpringApplication.run(BridgeApplication.class, args);
    }

    /**
     * Ensures the JACOB native DLL is discoverable by copying it into the
     * working directory (which is on {@code java.library.path} by default as ".").
     * This avoids requiring {@code -Djava.library.path} on the command line.
     *
     * <p>Search order for the source DLL:
     * <ol>
     *   <li>{@code <project-root>/lib/} (JAR in target/ or classes in target/classes)</li>
     *   <li>{@code <jar-dir>/lib/} (flat deployment next to the JAR)</li>
     * </ol>
     */
    private static void configureJacobLibraryPath() {
        String arch = System.getProperty("os.arch", "").contains("64") ? "x64" : "x86";
        String dllName = "jacob-1.21-" + arch + ".dll";

        // If the DLL is already in the working directory, nothing to do
        File cwdDll = new File(dllName);
        if (cwdDll.isFile()) {
            return;
        }

        try {
            String classPath = System.getProperty("java.class.path", "");
            String firstEntry = classPath.split(File.pathSeparator)[0];
            File cpFile = new File(firstEntry);

            Path[] candidates;
            if (cpFile.isFile() && cpFile.getName().endsWith(".jar")) {
                Path jarDir = cpFile.toPath().getParent();
                candidates = new Path[]{
                        jarDir.getParent().resolve("lib"),
                        jarDir.resolve("lib")
                };
            } else {
                Path projectRoot = cpFile.toPath().getParent().getParent();
                candidates = new Path[]{
                        projectRoot.resolve("lib")
                };
            }

            for (Path libDir : candidates) {
                File sourceDll = libDir.resolve(dllName).toFile();
                if (sourceDll.isFile()) {
                    java.nio.file.Files.copy(
                            sourceDll.toPath(),
                            cwdDll.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    );
                    cwdDll.deleteOnExit();
                    return;
                }
            }

            System.err.println("Warning: " + dllName + " not found; "
                    + "scanner will fail unless -Djava.library.path is set.");
        } catch (Exception e) {
            System.err.println("Warning: could not auto-configure JACOB DLL: " + e.getMessage());
        }
    }
}
