package com.scanner.bridge.converter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FileConverter}.
 *
 * <p>A real {@code FileConverter} instance is used — no mocks needed because the class has
 * no external collaborators. The BMP helper method mirrors the approach used in
 * {@code MockScannerImpl}: draw into a {@link BufferedImage} and encode with {@link ImageIO}.
 */
@ExtendWith(MockitoExtension.class)
class FileConverterTest {

    private FileConverter fileConverter;

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp() {
        fileConverter = new FileConverter();
    }

    // -------------------------------------------------------------------------
    // BMP helper
    // -------------------------------------------------------------------------

    /**
     * Generates a minimal 100x100 BMP image as a {@code byte[]} for use as converter input.
     * Uses the same {@code BufferedImage} + {@code ImageIO} approach as {@code MockScannerImpl}.
     */
    private byte[] createBmpBytes() throws Exception {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 100, 100);
        g.setColor(Color.BLACK);
        g.drawString("Test", 10, 50);
        g.dispose();

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(image, "BMP", bos);
        return bos.toByteArray();
    }

    // -------------------------------------------------------------------------
    // convert() — format tests
    // -------------------------------------------------------------------------

    @Test
    void convert_toPdf_returnsNonEmptyBytes() throws Exception {
        byte[] bmpBytes = createBmpBytes();

        byte[] result = fileConverter.convert(bmpBytes, "pdf");

        assertNotNull(result, "PDF result must not be null");
        assertTrue(result.length > 0, "PDF result must not be empty");
        // PDF magic bytes: %PDF  →  0x25 0x50 0x44 0x46
        assertEquals((byte) 0x25, result[0], "PDF must start with 0x25 ('%')");
        assertEquals((byte) 0x50, result[1], "PDF must start with 0x50 ('P')");
        assertEquals((byte) 0x44, result[2], "PDF must start with 0x44 ('D')");
        assertEquals((byte) 0x46, result[3], "PDF must start with 0x46 ('F')");
    }

    @Test
    void convert_toJpeg_returnsNonEmptyBytes() throws Exception {
        byte[] bmpBytes = createBmpBytes();

        byte[] result = fileConverter.convert(bmpBytes, "jpeg");

        assertNotNull(result, "JPEG result must not be null");
        assertTrue(result.length > 0, "JPEG result must not be empty");
        // JPEG magic bytes: 0xFF 0xD8
        assertEquals((byte) 0xFF, result[0], "JPEG must start with 0xFF");
        assertEquals((byte) 0xD8, result[1], "JPEG must start with 0xD8");
    }

    @Test
    void convert_toPng_returnsNonEmptyBytes() throws Exception {
        byte[] bmpBytes = createBmpBytes();

        byte[] result = fileConverter.convert(bmpBytes, "png");

        assertNotNull(result, "PNG result must not be null");
        assertTrue(result.length > 0, "PNG result must not be empty");
        // PNG magic bytes: 0x89 0x50 0x4E 0x47
        assertEquals((byte) 0x89, result[0], "PNG must start with 0x89");
        assertEquals((byte) 0x50, result[1], "PNG must start with 0x50 ('P')");
        assertEquals((byte) 0x4E, result[2], "PNG must start with 0x4E ('N')");
        assertEquals((byte) 0x47, result[3], "PNG must start with 0x47 ('G')");
    }

    @Test
    void convert_toTiff_returnsNonEmptyBytes() throws Exception {
        byte[] bmpBytes = createBmpBytes();

        byte[] result = fileConverter.convert(bmpBytes, "tiff");

        assertNotNull(result, "TIFF result must not be null");
        assertTrue(result.length > 0, "TIFF result must not be empty");
        // TIFF magic: 0x49 0x49 (little-endian "II") or 0x4D 0x4D (big-endian "MM")
        boolean isLittleEndian = result[0] == (byte) 0x49 && result[1] == (byte) 0x49;
        boolean isBigEndian    = result[0] == (byte) 0x4D && result[1] == (byte) 0x4D;
        assertTrue(isLittleEndian || isBigEndian,
                "TIFF must start with II (0x4949) or MM (0x4D4D), but got: "
                + String.format("0x%02X 0x%02X", result[0], result[1]));
    }

    // -------------------------------------------------------------------------
    // convert() — case-insensitivity
    // -------------------------------------------------------------------------

    @Test
    void convert_withUpperCaseFormat_works() throws Exception {
        byte[] bmpBytes = createBmpBytes();

        byte[] result = fileConverter.convert(bmpBytes, "PDF");

        assertNotNull(result, "Result must not be null for upper-case format");
        assertTrue(result.length > 0, "Result must not be empty for upper-case format");
        // Still expect a valid PDF header
        assertEquals((byte) 0x25, result[0]);
        assertEquals((byte) 0x50, result[1]);
        assertEquals((byte) 0x44, result[2]);
        assertEquals((byte) 0x46, result[3]);
    }

    // -------------------------------------------------------------------------
    // convert() — error cases
    // -------------------------------------------------------------------------

    @Test
    void convert_withNullFormat_throwsIllegalArgumentException() throws Exception {
        byte[] bmpBytes = createBmpBytes();

        assertThrows(IllegalArgumentException.class,
                () -> fileConverter.convert(bmpBytes, (String) null),
                "Null format must throw IllegalArgumentException");
    }

    @Test
    void convert_withUnsupportedFormat_throwsIllegalArgumentException() throws Exception {
        byte[] bmpBytes = createBmpBytes();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> fileConverter.convert(bmpBytes, "bmp"),
                "Unsupported format must throw IllegalArgumentException");
        assertTrue(ex.getMessage().contains("bmp"),
                "Exception message should mention the offending format");
    }

    // -------------------------------------------------------------------------
    // getMimeType()
    // -------------------------------------------------------------------------

    @Test
    void getMimeType_returnsCorrectMimeTypes() {
        assertEquals("application/pdf", fileConverter.getMimeType("pdf"));
        assertEquals("image/jpeg",      fileConverter.getMimeType("jpeg"));
        assertEquals("image/png",       fileConverter.getMimeType("png"));
        assertEquals("image/tiff",      fileConverter.getMimeType("tiff"));
    }

    // -------------------------------------------------------------------------
    // getFileExtension()
    // -------------------------------------------------------------------------

    @Test
    void getFileExtension_returnsCorrectExtensions() {
        assertEquals("pdf",  fileConverter.getFileExtension("pdf"));
        // jpeg maps to "jpg" (no 'e')
        assertEquals("jpg",  fileConverter.getFileExtension("jpeg"));
        assertEquals("png",  fileConverter.getFileExtension("png"));
        assertEquals("tiff", fileConverter.getFileExtension("tiff"));
    }
}
