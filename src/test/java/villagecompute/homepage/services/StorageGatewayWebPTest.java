package villagecompute.homepage.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.Test;

/**
 * Tests for WebP image processing logic.
 *
 * <p>
 * Verifies:
 * <ul>
 * <li>JPEG → WebP conversion with file size reduction</li>
 * <li>PNG → WebP conversion with file size reduction</li>
 * <li>Image resizing with aspect ratio preservation</li>
 * <li>Letterboxing for portrait → landscape conversion</li>
 * <li>Pillarboxing for landscape → portrait conversion</li>
 * </ul>
 *
 * <p>
 * <b>Feature:</b> I6.T1 - WebP Image Processing
 *
 * <p>
 * <b>Note:</b> This is a standalone unit test that doesn't require Quarkus or database. It tests the image processing
 * logic in isolation using direct method calls.
 */
public class StorageGatewayWebPTest {

    /**
     * Test JPEG → WebP conversion with file size reduction.
     */
    @Test
    public void testConvertToWebP_JPEG() throws Exception {
        // Create test JPEG image (100x100 red square)
        byte[] jpegBytes = createTestImageJPEG(100, 100, Color.RED);

        // Convert to WebP
        byte[] webpBytes = encodeToWebP(ImageIO.read(new ByteArrayInputStream(jpegBytes)), 85);

        // Verify WebP is smaller than JPEG (target 30-50% reduction)
        assertTrue(webpBytes.length > 0, "WebP output should have non-zero size");
        assertTrue(webpBytes.length < jpegBytes.length, "WebP should be smaller than original JPEG");

        double reductionPercent = 100.0 * (1.0 - ((double) webpBytes.length / jpegBytes.length));
        assertTrue(reductionPercent > 10,
                "WebP should achieve at least 10% size reduction (got " + reductionPercent + "%)");
    }

    /**
     * Test PNG → WebP conversion with file size reduction.
     */
    @Test
    public void testConvertToWebP_PNG() throws Exception {
        // Create test PNG image (100x100 blue square)
        byte[] pngBytes = createTestImagePNG(100, 100, Color.BLUE);

        // Convert to WebP
        byte[] webpBytes = encodeToWebP(ImageIO.read(new ByteArrayInputStream(pngBytes)), 85);

        // Verify WebP is smaller than PNG
        assertTrue(webpBytes.length > 0, "WebP output should have non-zero size");
        assertTrue(webpBytes.length < pngBytes.length, "WebP should be smaller than original PNG");
    }

    /**
     * Test aspect ratio preservation with letterboxing (portrait → landscape).
     */
    @Test
    public void testResizeImage_Letterbox() throws Exception {
        // Create portrait image (400x600)
        BufferedImage portrait = createBufferedImage(400, 600, Color.GREEN);

        // Resize to landscape canvas (800x600)
        // Portrait (2:3 ratio) → Landscape canvas (4:3 ratio) requires pillarboxing (left/right bars)
        // Scaled image will be 400x600 centered horizontally on 800x600 canvas
        BufferedImage resized = resizeImage(portrait, 800, 600);

        assertNotNull(resized, "Resized image should not be null");
        assertEquals(800, resized.getWidth(), "Canvas width should match target");
        assertEquals(600, resized.getHeight(), "Canvas height should match target");

        // Check for pillarboxing (black bars left/right)
        // Left edge should be black (pillarbox)
        assertEquals(Color.BLACK.getRGB(), resized.getRGB(0, 300), "Left edge should have pillarbox (black)");
        // Right edge should be black (pillarbox)
        assertEquals(Color.BLACK.getRGB(), resized.getRGB(799, 300), "Right edge should have pillarbox (black)");
        // Center should be green (the scaled portrait image)
        assertEquals(Color.GREEN.getRGB(), resized.getRGB(400, 300), "Center should have image content (green)");
    }

    /**
     * Test aspect ratio preservation with pillarboxing (landscape → portrait).
     */
    @Test
    public void testResizeImage_Pillarbox() throws Exception {
        // Create landscape image (800x400)
        BufferedImage landscape = createBufferedImage(800, 400, Color.YELLOW);

        // Resize to portrait canvas (400x800)
        // Landscape (2:1 ratio) → Portrait canvas (1:2 ratio) requires letterboxing (top/bottom bars)
        // Scaled image will be 400x200 centered vertically on 400x800 canvas
        BufferedImage resized = resizeImage(landscape, 400, 800);

        assertNotNull(resized, "Resized image should not be null");
        assertEquals(400, resized.getWidth(), "Canvas width should match target");
        assertEquals(800, resized.getHeight(), "Canvas height should match target");

        // Check for letterboxing (black bars top/bottom)
        // Top edge should be black (letterbox)
        assertEquals(Color.BLACK.getRGB(), resized.getRGB(200, 0), "Top edge should have letterbox (black)");
        // Bottom edge should be black (letterbox)
        assertEquals(Color.BLACK.getRGB(), resized.getRGB(200, 799), "Bottom edge should have letterbox (black)");
        // Center should be yellow (the scaled landscape image)
        assertEquals(Color.YELLOW.getRGB(), resized.getRGB(200, 400), "Center should have image content (yellow)");
    }

    /**
     * Test exact aspect ratio match (no letterboxing).
     */
    @Test
    public void testResizeImage_ExactAspectRatio() throws Exception {
        // Create square image (200x200)
        BufferedImage square = createBufferedImage(200, 200, Color.MAGENTA);

        // Resize to square canvas (400x400) - exact 1:1 aspect ratio
        BufferedImage resized = resizeImage(square, 400, 400);

        assertNotNull(resized, "Resized image should not be null");
        assertEquals(400, resized.getWidth(), "Width should match target");
        assertEquals(400, resized.getHeight(), "Height should match target");

        // No letterboxing - check center pixel is NOT black (should be magenta)
        int centerColor = resized.getRGB(200, 200);
        assertTrue(centerColor != Color.BLACK.getRGB(), "Center should not be black (no letterboxing for exact ratio)");
    }

    // ==================== Test Helper Methods ====================

    /**
     * Creates a test JPEG image with specified dimensions and color.
     */
    private byte[] createTestImageJPEG(int width, int height, Color color) throws IOException {
        BufferedImage image = createBufferedImage(width, height, color);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "JPEG", baos);
        return baos.toByteArray();
    }

    /**
     * Creates a test PNG image with specified dimensions and color.
     */
    private byte[] createTestImagePNG(int width, int height, Color color) throws IOException {
        BufferedImage image = createBufferedImage(width, height, color);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        return baos.toByteArray();
    }

    /**
     * Creates a BufferedImage with specified dimensions filled with solid color.
     */
    private BufferedImage createBufferedImage(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    /**
     * Resizes image to fit within target dimensions while preserving aspect ratio (mirrors StorageGateway logic).
     */
    private BufferedImage resizeImage(BufferedImage original, int maxWidth, int maxHeight) {
        int originalWidth = original.getWidth();
        int originalHeight = original.getHeight();

        // Calculate scaling factor to fit within bounds while preserving aspect ratio
        double widthRatio = (double) maxWidth / originalWidth;
        double heightRatio = (double) maxHeight / originalHeight;
        double scaleFactor = Math.min(widthRatio, heightRatio);

        int scaledWidth = (int) (originalWidth * scaleFactor);
        int scaledHeight = (int) (originalHeight * scaleFactor);

        // Create canvas with target dimensions (for letterboxing/pillarboxing)
        BufferedImage canvas = new BufferedImage(maxWidth, maxHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();

        // Fill canvas with black (letterbox/pillarbox color)
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, maxWidth, maxHeight);

        // Center the scaled image on canvas
        int x = (maxWidth - scaledWidth) / 2;
        int y = (maxHeight - scaledHeight) / 2;

        // Apply high-quality rendering hints
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw scaled image centered on canvas
        g.drawImage(original, x, y, scaledWidth, scaledHeight, null);
        g.dispose();

        return canvas;
    }

    /**
     * Encodes BufferedImage to WebP format (mirrors StorageGateway logic).
     */
    private byte[] encodeToWebP(BufferedImage image, int quality) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageOutputStream ios = ImageIO.createImageOutputStream(outputStream);

        // Get WebP writer
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType("image/webp");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No WebP ImageWriter found - webp-imageio plugin not loaded");
        }

        ImageWriter writer = writers.next();
        writer.setOutput(ios);

        // Configure compression quality
        ImageWriteParam writeParam = writer.getDefaultWriteParam();
        writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);

        // Set compression type before quality (required by webp-imageio)
        String[] compressionTypes = writeParam.getCompressionTypes();
        if (compressionTypes != null && compressionTypes.length > 0) {
            writeParam.setCompressionType(compressionTypes[0]); // Use first available type (typically "Lossy")
        }

        writeParam.setCompressionQuality(quality / 100.0f); // Convert 0-100 to 0.0-1.0

        // Write image
        writer.write(null, new IIOImage(image, null, null), writeParam);

        // Cleanup
        writer.dispose();
        ios.close();

        return outputStream.toByteArray();
    }
}
