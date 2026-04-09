package com.scanner.bridge.scanner;

import java.util.List;

public interface ScannerService {
    List<byte[]> scan(int maxPages) throws Exception;
    List<String> listScanners();
}
