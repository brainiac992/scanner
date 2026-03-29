package com.scanner.bridge.converter;

import com.scanner.bridge.model.ScanFormat;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spring service that converts raw scanner image bytes into various output formats.
 *
 * <p>Supported formats: pdf, jpeg, png, tiff.
 *
 * <p>Note: TIFF output relies on Java ImageIO's built-in TIFF support (available since Java 9).
 * If running on an older JVM without native TIFF support, add the {@code jai-imageio-core} plugin
 * (e.g. {@code com.github.jai-imageio:jai-imageio-core}) to the classpath.
 */
@Service
public class FileConverter {

    private static final Logger log = LoggerFactory.getLogger(FileConverter.class);

    /**
     * Converts raw image bytes into the specified output format.
     *
     * @param rawImageBytes the source image data as a byte array (any format readable by ImageIO)
     * @param format        the target format; one of {@code "pdf"}, {@code "jpeg"}, {@code "png"}, {@code "tiff"}
     * @return the converted image as a byte array in the requested format
     * @throws IllegalArgumentException if {@code format} is not one of the supported values
     * @throws Exception                if reading the source image or writing the output fails
     */
    public byte[] convert(byte[] rawImageBytes, String format) throws Exception {
        if (format == null) throw new IllegalArgumentException("Format must not be null");
        log.info("Converting image bytes to format: {}", format);

        BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(rawImageBytes));
        if (bufferedImage == null) {
            throw new IllegalArgumentException(
                    "Could not decode rawImageBytes — ensure the bytes represent a valid image.");
        }

        return switch (format.toLowerCase()) {
            case "pdf"  -> convertToPdf(rawImageBytes, bufferedImage);
            case "jpeg" -> convertToJpeg(bufferedImage);
            case "png"  -> convertToImageFormat(bufferedImage, "PNG");
            case "tiff" -> convertToImageFormat(bufferedImage, "TIFF");
            default -> throw new IllegalArgumentException(
                    "Unsupported format: \"" + format + "\". Supported formats are: pdf, jpeg, png, tiff.");
        };
    }

    /**
     * Overload that accepts a {@link ScanFormat} directly, delegating to the string-based method.
     */
    public byte[] convert(byte[] rawImageBytes, ScanFormat format) throws Exception {
        return convert(rawImageBytes, format.getKey());
    }

    // -------------------------------------------------------------------------
    // Private conversion helpers
    // -------------------------------------------------------------------------

    /**
     * Converts the image to a single-page PDF whose page dimensions exactly match the image.
     *
     * <p>The original {@code rawImageBytes} array is reused when embedding the image into PDFBox
     * so that re-encoding is avoided.
     */
    private byte[] convertToPdf(byte[] rawImageBytes, BufferedImage bufferedImage) throws Exception {
        log.debug("Creating PDF from image — width={} height={}", bufferedImage.getWidth(), bufferedImage.getHeight());

        try (PDDocument document = new PDDocument()) {
            float width  = bufferedImage.getWidth();
            float height = bufferedImage.getHeight();

            PDPage page = new PDPage(new PDRectangle(width, height));
            document.addPage(page);

            PDImageXObject pdImage = PDImageXObject.createFromByteArray(
                    document, rawImageBytes, "scanner-image");

            try (PDPageContentStream contentStream =
                         new PDPageContentStream(document, page,
                                 PDPageContentStream.AppendMode.OVERWRITE, false)) {
                contentStream.drawImage(pdImage, 0, 0, width, height);
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            document.save(outputStream);
            log.debug("PDF created successfully, size={} bytes", outputStream.size());
            return outputStream.toByteArray();
        }
    }

    /**
     * Converts the image to JPEG bytes.
     *
     * <p>JPEG does not support an alpha channel. If the source image has transparency
     * ({@code TYPE_INT_ARGB} or similar), it is composited onto a white background by
     * converting to {@code TYPE_INT_RGB} before encoding.
     */
    private byte[] convertToJpeg(BufferedImage bufferedImage) throws Exception {
        if (bufferedImage.getType() != BufferedImage.TYPE_INT_RGB) {
            log.debug("Source image has transparency or non-RGB type — converting to TYPE_INT_RGB for JPEG output.");
            BufferedImage rgbImage = new BufferedImage(
                    bufferedImage.getWidth(), bufferedImage.getHeight(), BufferedImage.TYPE_INT_RGB);
            rgbImage.createGraphics().drawImage(bufferedImage, 0, 0, java.awt.Color.WHITE, null);
            bufferedImage = rgbImage;
        }
        return convertToImageFormat(bufferedImage, "JPEG");
    }

    /**
     * Encodes a {@link BufferedImage} using the given ImageIO format name and returns the bytes.
     */
    private byte[] convertToImageFormat(BufferedImage bufferedImage, String imageIOFormat) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        boolean written = ImageIO.write(bufferedImage, imageIOFormat, outputStream);
        if (!written) {
            throw new IllegalStateException(
                    "ImageIO has no writer for format \"" + imageIOFormat
                    + "\". For TIFF support on older JVMs, add the jai-imageio-core library to the classpath.");
        }
        log.debug("{} output size={} bytes", imageIOFormat, outputStream.size());
        return outputStream.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Metadata helpers
    // -------------------------------------------------------------------------

    /**
     * Overload that accepts a {@link ScanFormat} directly.
     */
    public String getMimeType(ScanFormat format) {
        return format.getMimeType();
    }

    /**
     * Returns the MIME type corresponding to the given format string.
     *
     * @param format one of {@code "pdf"}, {@code "jpeg"}, {@code "png"}, {@code "tiff"}
     * @return the MIME type string
     * @throws IllegalArgumentException for unrecognised formats
     */
    public String getMimeType(String format) {
        return switch (format.toLowerCase()) {
            case "pdf"  -> "application/pdf";
            case "jpeg" -> "image/jpeg";
            case "png"  -> "image/png";
            case "tiff" -> "image/tiff";
            default -> throw new IllegalArgumentException(
                    "Unsupported format: \"" + format + "\". Supported formats are: pdf, jpeg, png, tiff.");
        };
    }

    /**
     * Overload that accepts a {@link ScanFormat} directly.
     */
    public String getFileExtension(ScanFormat format) {
        return format.getExtension();
    }

    /**
     * Returns the file extension corresponding to the given format string (without a leading dot).
     *
     * @param format one of {@code "pdf"}, {@code "jpeg"}, {@code "png"}, {@code "tiff"}
     * @return the file extension ({@code pdf}, {@code jpg}, {@code png}, or {@code tiff})
     * @throws IllegalArgumentException for unrecognised formats
     */
    public String getFileExtension(String format) {
        return switch (format.toLowerCase()) {
            case "pdf"  -> "pdf";
            case "jpeg" -> "jpg";
            case "png"  -> "png";
            case "tiff" -> "tiff";
            default -> throw new IllegalArgumentException(
                    "Unsupported format: \"" + format + "\". Supported formats are: pdf, jpeg, png, tiff.");
        };
    }
}
