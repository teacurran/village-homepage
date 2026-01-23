package villagecompute.homepage;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Abstract base class for tests requiring WireMock HTTP API mocking.
 *
 * <p>
 * Extends {@link BaseIntegrationTest} to inherit database and entity assertion helpers.
 *
 * <p>
 * Provides WireMock server lifecycle management:
 * <ul>
 * <li>Starts WireMock server on random port before each test</li>
 * <li>Stops and resets server after each test</li>
 * <li>Provides helper methods for stubbing external API responses</li>
 * </ul>
 *
 * <p>
 * <b>Usage Example:</b>
 *
 * <pre>
 * &#64;QuarkusTest
 * public class StockServiceTest extends WireMockTestBase {
 *     &#64;Test
 *     &#64;TestTransaction
 *     public void testFetchStockQuote() {
 *         stubAlphaVantageStockQuote("AAPL");
 *         // Test code that calls AlphaVantageClient...
 *     }
 * }
 * </pre>
 *
 * <p>
 * <b>External APIs Mocked:</b>
 * <ul>
 * <li>Alpha Vantage - Stock market data</li>
 * <li>Open-Meteo - International weather forecasts</li>
 * <li>National Weather Service (NWS) - US weather forecasts</li>
 * <li>Meta Graph API - Instagram/Facebook integration</li>
 * <li>Anthropic Claude - AI tagging and categorization</li>
 * </ul>
 *
 * <p>
 * <b>Ref:</b> Task I1.T5, Foundation Blueprint Section 3.5 (Testing Strategy: "Mock only at system boundaries")
 *
 * @see BaseIntegrationTest for database testing infrastructure
 */
public abstract class WireMockTestBase extends BaseIntegrationTest {

    /** WireMock HTTP server for stubbing external API responses. */
    protected WireMockServer wireMockServer;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();

        // Start WireMock server on random port to avoid conflicts
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();

        // Configure WireMock client to use this server
        WireMock.configureFor("localhost", wireMockServer.port());
    }

    @AfterEach
    @Override
    protected void tearDown() {
        // Stop and reset WireMock server to prevent test pollution
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.resetAll();
            wireMockServer.stop();
        }

        super.tearDown();
    }

    /**
     * Stubs a successful Alpha Vantage stock quote response for the given symbol.
     *
     * <p>
     * Uses the default success stub file: alpha-vantage/stock-quote-success.json
     *
     * <p>
     * Matches requests to: GET /query?function=GLOBAL_QUOTE&symbol={symbol}
     *
     * @param symbol
     *            the stock ticker symbol (e.g., "AAPL")
     * @throws RuntimeException
     *             if stub file cannot be loaded
     */
    protected void stubAlphaVantageStockQuote(String symbol) {
        String responseBody = loadStubFile("wiremock/alpha-vantage/stock-quote-success.json");
        stubAlphaVantageStockQuote(symbol, responseBody);
    }

    /**
     * Stubs an Alpha Vantage stock quote response with custom JSON.
     *
     * <p>
     * Matches requests to: GET /query?function=GLOBAL_QUOTE&symbol={symbol}
     *
     * @param symbol
     *            the stock ticker symbol (e.g., "AAPL")
     * @param responseBodyJson
     *            the custom JSON response body
     */
    protected void stubAlphaVantageStockQuote(String symbol, String responseBodyJson) {
        wireMockServer.stubFor(WireMock.get(WireMock.urlPathEqualTo("/query"))
                .withQueryParam("function", WireMock.equalTo("GLOBAL_QUOTE"))
                .withQueryParam("symbol", WireMock.equalTo(symbol)).willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(responseBodyJson)));
    }

    /**
     * Stubs a successful Open-Meteo weather forecast response for the given coordinates.
     *
     * <p>
     * Uses the default success stub file: open-meteo/weather-forecast-success.json
     *
     * <p>
     * Matches requests to: GET /v1/forecast?latitude={lat}&longitude={lon}
     *
     * @param latitude
     *            the latitude coordinate
     * @param longitude
     *            the longitude coordinate
     * @throws RuntimeException
     *             if stub file cannot be loaded
     */
    protected void stubOpenMeteoForecast(double latitude, double longitude) {
        String responseBody = loadStubFile("wiremock/open-meteo/weather-forecast-success.json");
        wireMockServer.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/forecast"))
                .withQueryParam("latitude", WireMock.equalTo(String.valueOf(latitude)))
                .withQueryParam("longitude", WireMock.equalTo(String.valueOf(longitude)))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(responseBody)));
    }

    /**
     * Stubs a successful NWS (National Weather Service) forecast response for the given grid coordinates.
     *
     * <p>
     * Uses the default success stub file: nws/weather-us-success.json
     *
     * <p>
     * Matches requests to: GET /gridpoints/{office}/{gridX},{gridY}/forecast
     *
     * @param office
     *            the NWS office code (e.g., "MTR" for San Francisco Bay Area)
     * @param gridX
     *            the grid X coordinate
     * @param gridY
     *            the grid Y coordinate
     * @throws RuntimeException
     *             if stub file cannot be loaded
     */
    protected void stubNwsForecast(String office, int gridX, int gridY) {
        String responseBody = loadStubFile("wiremock/nws/weather-us-success.json");
        wireMockServer.stubFor(
                WireMock.get(WireMock.urlPathEqualTo("/gridpoints/" + office + "/" + gridX + "," + gridY + "/forecast"))
                        .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                                .withBody(responseBody)));
    }

    /**
     * Stubs a successful Meta Graph API Instagram posts response for the given user ID.
     *
     * <p>
     * Uses the default success stub file: meta-graph/instagram-posts-success.json
     *
     * <p>
     * Matches requests to: GET /v18.0/{userId}/media
     *
     * @param userId
     *            the Instagram user ID
     * @throws RuntimeException
     *             if stub file cannot be loaded
     */
    protected void stubMetaGraphInstagramPosts(String userId) {
        String responseBody = loadStubFile("wiremock/meta-graph/instagram-posts-success.json");
        wireMockServer.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v18.0/" + userId + "/media")).willReturn(WireMock
                .aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(responseBody)));
    }

    /**
     * Stubs a successful Anthropic Claude AI tagging response.
     *
     * <p>
     * Uses the default success stub file: anthropic/ai-tagging-success.json
     *
     * <p>
     * Matches requests to: POST /v1/messages
     *
     * @throws RuntimeException
     *             if stub file cannot be loaded
     */
    protected void stubAnthropicAiTagging() {
        String responseBody = loadStubFile("wiremock/anthropic/ai-tagging-success.json");
        stubAnthropicAiTagging(responseBody);
    }

    /**
     * Stubs an Anthropic Claude AI tagging response with custom JSON.
     *
     * <p>
     * Matches requests to: POST /v1/messages
     *
     * @param responseBodyJson
     *            the custom JSON response body
     */
    protected void stubAnthropicAiTagging(String responseBodyJson) {
        wireMockServer.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/messages")).willReturn(WireMock.aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(responseBodyJson)));
    }

    /**
     * Stubs a successful Facebook OAuth token exchange response.
     *
     * <p>
     * Uses the default success stub file: facebook-oauth/token-response-success.json
     *
     * <p>
     * Matches requests to: POST /oauth/access_token
     *
     * @throws RuntimeException
     *             if stub file cannot be loaded
     */
    protected void stubFacebookTokenExchange() {
        String responseBody = loadStubFile("wiremock/facebook-oauth/token-response-success.json");
        stubFacebookTokenExchange(responseBody);
    }

    /**
     * Stubs a Facebook OAuth token exchange response with custom JSON.
     *
     * <p>
     * Matches requests to: POST /oauth/access_token
     *
     * @param responseBodyJson
     *            the custom JSON response body
     */
    protected void stubFacebookTokenExchange(String responseBodyJson) {
        wireMockServer
                .stubFor(WireMock.post(WireMock.urlPathEqualTo("/oauth/access_token")).willReturn(WireMock.aResponse()
                        .withStatus(200).withHeader("Content-Type", "application/json").withBody(responseBodyJson)));
    }

    /**
     * Stubs a successful Facebook Graph API user info response.
     *
     * <p>
     * Uses the default success stub file: facebook-oauth/user-info-success.json
     *
     * <p>
     * Matches requests to: GET /me?fields=id,name,email,picture
     *
     * @throws RuntimeException
     *             if stub file cannot be loaded
     */
    protected void stubFacebookUserInfo() {
        String responseBody = loadStubFile("wiremock/facebook-oauth/user-info-success.json");
        stubFacebookUserInfo(responseBody);
    }

    /**
     * Stubs a Facebook Graph API user info response with custom JSON.
     *
     * <p>
     * Matches requests to: GET /me?fields=id,name,email,picture
     *
     * @param responseBodyJson
     *            the custom JSON response body
     */
    protected void stubFacebookUserInfo(String responseBodyJson) {
        wireMockServer.stubFor(WireMock.get(WireMock.urlPathEqualTo("/me"))
                .withQueryParam("fields", WireMock.equalTo("id,name,email,picture")).willReturn(WireMock.aResponse()
                        .withStatus(200).withHeader("Content-Type", "application/json").withBody(responseBodyJson)));
    }

    /**
     * Stubs a successful Google OAuth token exchange response.
     *
     * <p>
     * Uses the default success stub file: google-oauth/token-response-success.json
     *
     * <p>
     * Matches requests to: POST /token
     *
     * @throws RuntimeException
     *             if stub file cannot be loaded
     */
    protected void stubGoogleTokenExchange() {
        String responseBody = loadStubFile("wiremock/google-oauth/token-response-success.json");
        stubGoogleTokenExchange(responseBody);
    }

    /**
     * Stubs a Google OAuth token exchange response with custom JSON.
     *
     * <p>
     * Matches requests to: POST /token
     *
     * @param responseBodyJson
     *            the custom JSON response body
     */
    protected void stubGoogleTokenExchange(String responseBodyJson) {
        wireMockServer.stubFor(WireMock.post(WireMock.urlPathEqualTo("/token")).willReturn(WireMock.aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(responseBodyJson)));
    }

    /**
     * Stubs a Google OAuth token exchange error response.
     *
     * <p>
     * Uses the error stub file: google-oauth/token-response-invalid-code.json
     *
     * <p>
     * Returns HTTP 400 Bad Request
     *
     * @throws RuntimeException
     *             if stub file cannot be loaded
     */
    protected void stubGoogleTokenExchangeError() {
        String responseBody = loadStubFile("wiremock/google-oauth/token-response-invalid-code.json");
        wireMockServer.stubFor(WireMock.post(WireMock.urlPathEqualTo("/token")).willReturn(WireMock.aResponse()
                .withStatus(400).withHeader("Content-Type", "application/json").withBody(responseBody)));
    }

    /**
     * Stubs a successful Google OAuth user info response.
     *
     * <p>
     * Uses the default success stub file: google-oauth/userinfo-response-success.json
     *
     * <p>
     * Matches requests to: GET /oauth2/v3/userinfo
     *
     * @throws RuntimeException
     *             if stub file cannot be loaded
     */
    protected void stubGoogleUserInfo() {
        String responseBody = loadStubFile("wiremock/google-oauth/userinfo-response-success.json");
        stubGoogleUserInfo(responseBody);
    }

    /**
     * Stubs a Google OAuth user info response with custom JSON.
     *
     * <p>
     * Matches requests to: GET /oauth2/v3/userinfo
     *
     * @param responseBodyJson
     *            the custom JSON response body
     */
    protected void stubGoogleUserInfo(String responseBodyJson) {
        wireMockServer.stubFor(WireMock.get(WireMock.urlPathEqualTo("/oauth2/v3/userinfo")).willReturn(WireMock
                .aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(responseBodyJson)));
    }

    /**
     * Stubs a successful Apple OAuth token exchange response.
     *
     * <p>
     * Uses the default success stub file: apple-oauth/token-response-success.json
     *
     * <p>
     * Matches requests to: POST /auth/token
     *
     * @throws RuntimeException
     *             if stub file cannot be loaded
     */
    protected void stubAppleTokenExchange() {
        String responseBody = loadStubFile("wiremock/apple-oauth/token-response-success.json");
        stubAppleTokenExchange(responseBody);
    }

    /**
     * Stubs an Apple OAuth token exchange response with custom JSON.
     *
     * <p>
     * Matches requests to: POST /auth/token
     *
     * @param responseBodyJson
     *            the custom JSON response body
     */
    protected void stubAppleTokenExchange(String responseBodyJson) {
        wireMockServer.stubFor(WireMock.post(WireMock.urlPathEqualTo("/auth/token")).willReturn(WireMock.aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(responseBodyJson)));
    }

    /**
     * Stubs an Apple OAuth token exchange error response.
     *
     * <p>
     * Uses the error stub file: apple-oauth/token-response-invalid-code.json
     *
     * <p>
     * Returns HTTP 400 Bad Request
     *
     * @throws RuntimeException
     *             if stub file cannot be loaded
     */
    protected void stubAppleTokenExchangeError() {
        String responseBody = loadStubFile("wiremock/apple-oauth/token-response-invalid-code.json");
        wireMockServer.stubFor(WireMock.post(WireMock.urlPathEqualTo("/auth/token")).willReturn(WireMock.aResponse()
                .withStatus(400).withHeader("Content-Type", "application/json").withBody(responseBody)));
    }

    /**
     * Loads a stub JSON file from the test resources directory.
     *
     * <p>
     * This is a helper method for loading stub response data from src/test/resources/
     *
     * @param resourcePath
     *            the path to the stub file (relative to src/test/resources/)
     * @return the stub file contents as a UTF-8 string
     * @throws RuntimeException
     *             if the file cannot be read or does not exist
     */
    protected String loadStubFile(String resourcePath) {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new RuntimeException("Stub file not found in test resources: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load stub file: " + resourcePath, e);
        }
    }
}
