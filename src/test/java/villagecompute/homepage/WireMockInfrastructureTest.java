package villagecompute.homepage;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import jakarta.transaction.Transactional;
import villagecompute.homepage.testing.PostgreSQLTestProfile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the WireMock infrastructure to ensure HTTP API mocking works correctly.
 *
 * <p>
 * This test verifies:
 * <ul>
 * <li>WireMock server starts and stops correctly</li>
 * <li>Stub JSON files load successfully</li>
 * <li>Helper methods configure stubs correctly</li>
 * <li>HTTP requests to WireMock server return expected responses</li>
 * </ul>
 *
 * <p>
 * <b>Ref:</b> Task I1.T5
 */
@QuarkusTest
@TestProfile(PostgreSQLTestProfile.class)
public class WireMockInfrastructureTest extends WireMockTestBase {

    @Test
    @Transactional
    public void testWireMockServerStarts() {
        assertNotNull(wireMockServer, "WireMock server should be initialized");
        assertTrue(wireMockServer.isRunning(), "WireMock server should be running");
        assertTrue(wireMockServer.port() > 0, "WireMock server should be using a valid port");
    }

    @Test
    @Transactional
    public void testAlphaVantageStubLoads() throws Exception {
        // Configure stub
        stubAlphaVantageStockQuote("AAPL");

        // Make HTTP request to WireMock server
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + wireMockServer.port() + "/query?function=GLOBAL_QUOTE&symbol=AAPL"))
                .GET().build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Verify response
        assertEquals(200, response.statusCode(), "Should return 200 OK");
        assertTrue(response.body().contains("Global Quote"), "Response should contain 'Global Quote'");
        assertTrue(response.body().contains("AAPL"), "Response should contain stock symbol");
        assertTrue(response.body().contains("174.50"), "Response should contain stock price");
    }

    @Test
    @Transactional
    public void testOpenMeteoStubLoads() throws Exception {
        // Configure stub
        stubOpenMeteoForecast(37.7749, -122.4194);

        // Make HTTP request to WireMock server
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(new URI(
                "http://localhost:" + wireMockServer.port() + "/v1/forecast?latitude=37.7749&longitude=-122.4194"))
                .GET().build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Verify response
        assertEquals(200, response.statusCode(), "Should return 200 OK");
        assertTrue(response.body().contains("hourly"), "Response should contain hourly forecast");
        assertTrue(response.body().contains("daily"), "Response should contain daily forecast");
        assertTrue(response.body().contains("temperature_2m"), "Response should contain temperature data");
    }

    @Test
    @Transactional
    public void testNwsStubLoads() throws Exception {
        // Configure stub
        stubNwsForecast("MTR", 90, 105);

        // Make HTTP request to WireMock server
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + wireMockServer.port() + "/gridpoints/MTR/90,105/forecast")).GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Verify response
        assertEquals(200, response.statusCode(), "Should return 200 OK");
        assertTrue(response.body().contains("periods"), "Response should contain forecast periods");
        assertTrue(response.body().contains("Partly Cloudy"), "Response should contain forecast description");
    }

    @Test
    @Transactional
    public void testMetaGraphStubLoads() throws Exception {
        // Configure stub
        stubMetaGraphInstagramPosts("17841405793187218");

        // Make HTTP request to WireMock server
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + wireMockServer.port() + "/v18.0/17841405793187218/media")).GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Verify response
        assertEquals(200, response.statusCode(), "Should return 200 OK");
        assertTrue(response.body().contains("data"), "Response should contain data array");
        assertTrue(response.body().contains("media_type"), "Response should contain media_type field");
        assertTrue(response.body().contains("paging"), "Response should contain paging object");
    }

    @Test
    @Transactional
    public void testAnthropicStubLoads() throws Exception {
        // Configure stub
        stubAnthropicAiTagging();

        // Make HTTP request to WireMock server
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + wireMockServer.port() + "/v1/messages"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"claude-3-5-sonnet-20241022\"}"))
                .header("Content-Type", "application/json").build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Verify response
        assertEquals(200, response.statusCode(), "Should return 200 OK");
        assertTrue(response.body().contains("content"), "Response should contain content array");
        assertTrue(response.body().contains("topics"), "Response should contain topics");
        assertTrue(response.body().contains("technology"), "Response should contain topic 'technology'");
    }

    @Test
    @Transactional
    public void testCustomStubResponse() throws Exception {
        // Configure stub with custom response
        String customResponse = "{\"Global Quote\":{\"01. symbol\":\"GOOGL\",\"05. price\":\"140.25\"}}";
        stubAlphaVantageStockQuote("GOOGL", customResponse);

        // Make HTTP request to WireMock server
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + wireMockServer.port() + "/query?function=GLOBAL_QUOTE&symbol=GOOGL"))
                .GET().build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Verify custom response
        assertEquals(200, response.statusCode(), "Should return 200 OK");
        assertTrue(response.body().contains("GOOGL"), "Response should contain custom stock symbol");
        assertTrue(response.body().contains("140.25"), "Response should contain custom stock price");
    }

    @Test
    @Transactional
    public void testServerResetBetweenTests() {
        // This test verifies that stubs from previous tests don't leak
        // WireMock server should be clean at the start of each test

        // Try to make a request without configuring a stub - should fail
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(
                            "http://localhost:" + wireMockServer.port() + "/query?function=GLOBAL_QUOTE&symbol=AAPL"))
                    .GET().build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // WireMock returns 404 when no stub matches
            assertEquals(404, response.statusCode(), "Should return 404 when no stub configured");
        } catch (Exception e) {
            fail("Should not throw exception when stub is not configured: " + e.getMessage());
        }
    }
}
