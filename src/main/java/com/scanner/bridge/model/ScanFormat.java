package com.scanner.bridge.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * Canonical list of supported scan output formats.
 * Single source of truth — used by both the handler (validation) and converter (output).
 */
public enum ScanFormat {

    PDF("pdf",  "application/pdf",  "pdf"),
    JPEG("jpeg", "image/jpeg",       "jpg"),
    PNG("png",  "image/png",         "png"),
    TIFF("tiff", "image/tiff",       "tiff");

    private final String key;
    private final String mimeType;
    private final String extension;

    ScanFormat(String key, String mimeType, String extension) {
        this.key = key;
        this.mimeType = mimeType;
        this.extension = extension;
    }

    public String getKey()       { return key; }
    public String getMimeType()  { return mimeType; }
    public String getExtension() { return extension; }

    /** Case-insensitive lookup. Returns empty if the value is not a supported format. */
    public static Optional<ScanFormat> fromKey(String key) {
        if (key == null) return Optional.empty();
        return Arrays.stream(values())
                .filter(f -> f.key.equalsIgnoreCase(key))
                .findFirst();
    }
}
