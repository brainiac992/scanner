package com.scanner.bridge.scanner;

import java.util.List;

public interface ScannerService {
    byte[] scan() throws Exception;
    List<String> listScanners();
}
