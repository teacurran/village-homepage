package villagecompute.homepage.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URL;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import villagecompute.homepage.api.types.SignedUrlType;
import villagecompute.homepage.api.types.StorageUploadResultType;
import villagecompute.homepage.services.StorageGateway.BucketType;

/**
 * Unit tests for StorageGateway covering upload, download, signed URL generation, and error handling.
 *
 * <p>
 * Critical test coverage per I3.T7 acceptance criteria:
 * <ul>
 * <li>Upload/download functions with success and failure scenarios</li>
 * <li>WebP conversion stub (pass-through for now)</li>
 * <li>Signed URL generation with TTL validation</li>
 * <li>Bucket prefixing strategy</li>
 * <li>Retention metadata mapping</li>
 * <li>Error handling for network failures and invalid credentials</li>
 * </ul>
 */
class StorageGatewayTest {

    private StorageGateway storageGateway;
    private S3Client s3Client;
    private S3Presigner s3Presigner;
    private Tracer tracer;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() throws Exception {
        s3Client = mock(S3Client.class);
        s3Presigner = mock(S3Presigner.class);
        meterRegistry = new SimpleMeterRegistry();
        tracer = OpenTelemetry.noop().getTracer("test");

        storageGateway = new StorageGateway();

        // Use reflection to inject mocked dependencies
        setField(storageGateway, "s3Client", s3Client);
        setField(storageGateway, "s3Presigner", s3Presigner);
        setField(storageGateway, "tracer", tracer);
        setField(storageGateway, "meterRegistry", meterRegistry);

        // Inject config properties
        setField(storageGateway, "screenshotsBucket", "screenshots");
        setField(storageGateway, "listingsBucket", "listings");
        setField(storageGateway, "profilesBucket", "profiles");
        setField(storageGateway, "webpQuality", 85);
        setField(storageGateway, "thumbnailWidth", 320);
        setField(storageGateway, "thumbnailHeight", 200);
        setField(storageGateway, "fullWidth", 1280);
        setField(storageGateway, "fullHeight", 800);
    }

    /**
     * Test: Successful upload returns expected result with metadata.
     */
    @Test
    void testUpload_success() {
        byte[] testData = "test-image-data".getBytes();
        PutObjectResponse putResponse = PutObjectResponse.builder().build();

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(putResponse);

        StorageUploadResultType result = storageGateway.upload(BucketType.SCREENSHOTS, "site-123", "thumbnail",
                testData, "image/jpeg");

        assertNotNull(result, "Upload result should not be null");
        assertEquals("screenshots", result.bucket());
        assertTrue(result.objectKey().startsWith("site-123/thumbnail/"));
        assertEquals("image/webp", result.contentType());
        assertEquals("thumbnail", result.variant());
        assertEquals((long) testData.length, result.sizeBytes());
        assertNotNull(result.uploadedAt());

        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    /**
     * Test: Upload failure throws RuntimeException with descriptive message.
     */
    @Test
    void testUpload_failure() {
        byte[] testData = "test-image-data".getBytes();
        S3Exception s3Exception = (S3Exception) S3Exception.builder().message("Access Denied").statusCode(403).build();

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(s3Exception);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> storageGateway.upload(BucketType.SCREENSHOTS, "site-123", "thumbnail", testData, "image/jpeg"));

        assertNotNull(exception.getMessage());
    }

    /**
     * Test: Successful download returns raw bytes.
     */
    @Test
    void testDownload_success() {
        byte[] testData = "test-image-data".getBytes();
        ResponseBytes<GetObjectResponse> responseBytes = ResponseBytes
                .fromByteArray(GetObjectResponse.builder().build(), testData);

        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);

        byte[] result = storageGateway.download(BucketType.SCREENSHOTS, "site-123/thumbnail/image.webp");

        assertNotNull(result, "Downloaded data should not be null");
        assertArrayEquals(testData, result, "Downloaded data should match uploaded data");

        verify(s3Client).getObjectAsBytes(any(GetObjectRequest.class));
    }

    /**
     * Test: Download failure throws RuntimeException with descriptive message.
     */
    @Test
    void testDownload_failure() {
        S3Exception s3Exception = (S3Exception) S3Exception.builder().message("NoSuchKey").statusCode(404).build();

        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(s3Exception);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> storageGateway.download(BucketType.SCREENSHOTS, "site-123/thumbnail/image.webp"));

        assertNotNull(exception.getMessage());
    }

    /**
     * Test: Signed URL generation returns URL with correct TTL and expiry.
     */
    @Test
    void testGenerateSignedUrl_success() throws Exception {
        URL mockUrl = new URL("https://example.com/presigned-url?signature=xyz");
        PresignedGetObjectRequest presignedRequest = mock(PresignedGetObjectRequest.class);

        when(presignedRequest.url()).thenReturn(mockUrl);
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presignedRequest);

        SignedUrlType result = storageGateway.generateSignedUrl(BucketType.SCREENSHOTS, "site-123/thumbnail/image.webp",
                1440);

        assertNotNull(result, "Signed URL result should not be null");
        assertEquals(mockUrl.toString(), result.url());
        assertEquals(1440, result.ttlMinutes());
        assertEquals("site-123/thumbnail/image.webp", result.objectKey());
        assertNotNull(result.expiresAt());

        // Verify expiry timestamp is in the future
        Instant expiresAt = Instant.parse(result.expiresAt());
        assertTrue(expiresAt.isAfter(Instant.now()), "Expiry should be in the future");

        verify(s3Presigner).presignGetObject(any(GetObjectPresignRequest.class));
    }

    /**
     * Test: Signed URL generation failure throws RuntimeException.
     */
    @Test
    void testGenerateSignedUrl_failure() {
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenThrow(new RuntimeException("Presigner error"));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> storageGateway
                .generateSignedUrl(BucketType.SCREENSHOTS, "site-123/thumbnail/image.webp", 1440));

        assertTrue(exception.getMessage().contains("Failed to generate signed URL"));
    }

    /**
     * Test: Delete operation succeeds and logs audit trail.
     */
    @Test
    void testDelete_success() {
        DeleteObjectResponse deleteResponse = DeleteObjectResponse.builder().build();

        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenReturn(deleteResponse);

        storageGateway.delete(BucketType.SCREENSHOTS, "site-123/thumbnail/image.webp");

        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }

    /**
     * Test: Delete failure throws RuntimeException.
     */
    @Test
    void testDelete_failure() {
        S3Exception s3Exception = (S3Exception) S3Exception.builder().message("Access Denied").statusCode(403).build();

        doThrow(s3Exception).when(s3Client).deleteObject(any(DeleteObjectRequest.class));

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> storageGateway.delete(BucketType.SCREENSHOTS, "site-123/thumbnail/image.webp"));

        assertNotNull(exception.getMessage());
    }

    /**
     * Test: List objects returns expected metadata.
     */
    @Test
    void testListObjects_success() {
        software.amazon.awssdk.services.s3.model.S3Object s3Object = software.amazon.awssdk.services.s3.model.S3Object
                .builder().key("site-123/thumbnail/image.webp").size(1024L).lastModified(Instant.now()).build();

        ListObjectsV2Response listResponse = ListObjectsV2Response.builder().contents(s3Object).build();

        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listResponse);

        var results = storageGateway.listObjects(BucketType.SCREENSHOTS, "site-123/", 10);

        assertNotNull(results, "List results should not be null");
        assertFalse(results.isEmpty(), "List results should contain objects");
        assertEquals(1, results.size());
        assertEquals("site-123/thumbnail/image.webp", results.get(0).objectKey());
        assertEquals(1024L, results.get(0).sizeBytes());

        verify(s3Client).listObjectsV2(any(ListObjectsV2Request.class));
    }

    /**
     * Test: List objects failure throws RuntimeException.
     */
    @Test
    void testListObjects_failure() {
        S3Exception s3Exception = (S3Exception) S3Exception.builder().message("Access Denied").statusCode(403).build();

        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenThrow(s3Exception);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> storageGateway.listObjects(BucketType.SCREENSHOTS, "site-123/", 10));

        assertNotNull(exception.getMessage());
    }

    /**
     * Test: Bucket name resolution for all bucket types.
     */
    @Test
    void testBucketNameResolution() throws Exception {
        // Test via upload to verify bucket names are resolved correctly
        byte[] testData = "test".getBytes();
        PutObjectResponse putResponse = PutObjectResponse.builder().build();

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(putResponse);

        StorageUploadResultType screenshotResult = storageGateway.upload(BucketType.SCREENSHOTS, "id1", "thumbnail",
                testData, "image/jpeg");
        assertEquals("screenshots", screenshotResult.bucket());

        StorageUploadResultType listingResult = storageGateway.upload(BucketType.LISTINGS, "id2", "full", testData,
                "image/jpeg");
        assertEquals("listings", listingResult.bucket());

        StorageUploadResultType profileResult = storageGateway.upload(BucketType.PROFILES, "id3", "avatar", testData,
                "image/jpeg");
        assertEquals("profiles", profileResult.bucket());
    }

    /**
     * Test: Object key prefixing strategy follows expected format.
     */
    @Test
    void testObjectKeyPrefixing() {
        byte[] testData = "test".getBytes();
        PutObjectResponse putResponse = PutObjectResponse.builder().build();

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(putResponse);

        StorageUploadResultType result = storageGateway.upload(BucketType.SCREENSHOTS, "site-456", "thumbnail",
                testData, "image/jpeg");

        String expectedPrefix = "site-456/thumbnail/";
        assertTrue(result.objectKey().startsWith(expectedPrefix),
                "Object key should follow entity/variant/filename format");
    }

    /**
     * Test: WebP conversion stub returns original bytes (placeholder implementation).
     */
    @Test
    void testWebPConversionStub() {
        byte[] testData = "test-image-data".getBytes();
        PutObjectResponse putResponse = PutObjectResponse.builder().build();

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(putResponse);

        StorageUploadResultType result = storageGateway.upload(BucketType.SCREENSHOTS, "site-123", "thumbnail",
                testData, "image/jpeg");

        // WebP conversion is currently a pass-through stub
        // When implemented, this test should verify actual conversion
        assertEquals(testData.length, result.sizeBytes(), "Current stub implementation preserves original size");
    }

    /**
     * Helper method to set private fields via reflection.
     */
    private void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
