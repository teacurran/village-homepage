/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.api.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import villagecompute.homepage.api.types.StockQuoteType;
import villagecompute.homepage.api.types.StockWidgetType;
import villagecompute.homepage.api.types.ThemeType;
import villagecompute.homepage.api.types.UserPreferencesType;
import villagecompute.homepage.services.RateLimitService;
import villagecompute.homepage.services.StockService;
import villagecompute.homepage.services.UserPreferenceService;

/**
 * Unit tests for {@link StockWidgetResource}.
 */
class StockWidgetResourceTest {

    @Mock
    StockService stockService;

    @Mock
    UserPreferenceService userPreferenceService;

    @Mock
    RateLimitService rateLimitService;

    @Mock
    SecurityContext securityContext;

    @Mock
    Principal principal;

    @InjectMocks
    StockWidgetResource resource;

    private UUID testUserId;
    private StockWidgetType mockStockData;
    private UserPreferencesType mockPreferences;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        testUserId = UUID.randomUUID();

        List<StockQuoteType> quotes = List.of(
                new StockQuoteType("AAPL", "Apple Inc.", 150.50, 1.25, 0.84,
                        List.of(149.00, 149.50, 150.00, 150.25, 150.50), "2026-01-09T10:00:00Z"),
                new StockQuoteType("GOOGL", "Alphabet Inc.", 140.75, -0.50, -0.35,
                        List.of(141.00, 140.80, 140.60, 140.70, 140.75), "2026-01-09T10:00:00Z"));

        mockStockData = new StockWidgetType(quotes, "OPEN", "2026-01-09T10:00:00Z", false, false);

        mockPreferences = new UserPreferencesType(1, List.of(), List.of(), List.of("AAPL", "GOOGL"), List.of(),
                new ThemeType("system", null, "standard"), java.util.Map.of());
    }

    @Test
    void testGetStocks_success() {
        // Arrange
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(testUserId.toString());
        when(rateLimitService.checkLimit(any(), any(), anyString(), any(RateLimitService.Tier.class), anyString()))
                .thenReturn(new RateLimitService.RateLimitResult(true, 100, 99, 3600));
        when(userPreferenceService.getPreferences(testUserId)).thenReturn(mockPreferences);
        when(stockService.getQuotes(anyList())).thenReturn(mockStockData);

        // Act
        Response response = resource.getStocks(securityContext);

        // Assert
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        StockWidgetType data = (StockWidgetType) response.getEntity();
        assertEquals(2, data.quotes().size());
        assertEquals("AAPL", data.quotes().get(0).symbol());
        assertEquals("OPEN", data.marketStatus());

        verify(stockService).getQuotes(List.of("AAPL", "GOOGL"));
    }

    @Test
    void testGetStocks_emptyWatchlist_returnsEmptyWidget() {
        // Arrange
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(testUserId.toString());
        when(rateLimitService.checkLimit(any(), any(), anyString(), any(RateLimitService.Tier.class), anyString()))
                .thenReturn(new RateLimitService.RateLimitResult(true, 100, 99, 3600));

        UserPreferencesType emptyPrefs = new UserPreferencesType(1, List.of(), List.of(), List.of(), // No watchlist
                List.of(), new ThemeType("system", null, "standard"), java.util.Map.of());
        when(userPreferenceService.getPreferences(testUserId)).thenReturn(emptyPrefs);
        when(stockService.getMarketStatus()).thenReturn("CLOSED");

        // Act
        Response response = resource.getStocks(securityContext);

        // Assert
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        StockWidgetType data = (StockWidgetType) response.getEntity();
        assertEquals(0, data.quotes().size());
        assertEquals("CLOSED", data.marketStatus());
    }

    @Test
    void testGetStocks_rateLimitExceeded_returns429() {
        // Arrange
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(testUserId.toString());
        when(rateLimitService.checkLimit(any(), any(), anyString(), any(RateLimitService.Tier.class), anyString()))
                .thenReturn(new RateLimitService.RateLimitResult(false, 100, 0, 3600));

        // Act
        Response response = resource.getStocks(securityContext);

        // Assert
        assertEquals(Response.Status.TOO_MANY_REQUESTS.getStatusCode(), response.getStatus());
        assertEquals("0", response.getHeaderString("X-RateLimit-Remaining"));
    }

    @Test
    void testUpdateWatchlist_success() {
        // Arrange
        List<String> newWatchlist = List.of("AAPL", "MSFT", "GOOGL");

        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(testUserId.toString());
        when(userPreferenceService.getPreferences(testUserId)).thenReturn(mockPreferences);

        // Act
        Response response = resource.updateWatchlist(newWatchlist, securityContext);

        // Assert
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        verify(userPreferenceService).updatePreferences(any(UUID.class), any(UserPreferencesType.class));
    }

    @Test
    void testUpdateWatchlist_exceedsLimit_returns400() {
        // Arrange - Create watchlist with 21 symbols (over limit of 20)
        List<String> oversizedWatchlist = List.of("AAPL", "MSFT", "GOOGL", "AMZN", "TSLA", "META", "NVDA", "JPM", "V",
                "WMT", "JNJ", "PG", "UNH", "HD", "BAC", "DIS", "ADBE", "NFLX", "PYPL", "INTC", "CSCO");

        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(testUserId.toString());

        // Act
        Response response = resource.updateWatchlist(oversizedWatchlist, securityContext);

        // Assert
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(response.getEntity().toString().contains("limited to 20 symbols"));
    }

    @Test
    void testUpdateWatchlist_invalidSymbolFormat_returns400() {
        // Arrange - Include lowercase symbol (invalid)
        List<String> invalidWatchlist = List.of("AAPL", "msft");

        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(testUserId.toString());

        // Act
        Response response = resource.updateWatchlist(invalidWatchlist, securityContext);

        // Assert
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(response.getEntity().toString().contains("Invalid symbol format"));
    }

    @Test
    void testUpdateWatchlist_validIndexSymbol_success() {
        // Arrange - Index symbols use caret (^)
        List<String> indexWatchlist = List.of("^GSPC", "^DJI", "^IXIC");

        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(testUserId.toString());
        when(userPreferenceService.getPreferences(testUserId)).thenReturn(mockPreferences);

        // Act
        Response response = resource.updateWatchlist(indexWatchlist, securityContext);

        // Assert
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        verify(userPreferenceService).updatePreferences(any(UUID.class), any(UserPreferencesType.class));
    }

    @Test
    void testGetStocks_cacheHeaderDuringMarketHours() {
        // Arrange
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(testUserId.toString());
        when(rateLimitService.checkLimit(any(), any(), anyString(), any(RateLimitService.Tier.class), anyString()))
                .thenReturn(new RateLimitService.RateLimitResult(true, 100, 99, 3600));
        when(userPreferenceService.getPreferences(testUserId)).thenReturn(mockPreferences);
        when(stockService.getQuotes(anyList())).thenReturn(mockStockData);
        when(stockService.isMarketOpen()).thenReturn(true);

        // Act
        Response response = resource.getStocks(securityContext);

        // Assert
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        String cacheControl = response.getHeaderString("Cache-Control");
        assertEquals("max-age=300", cacheControl); // 5 minutes during market hours
    }

    @Test
    void testGetStocks_cacheHeaderAfterHours() {
        // Arrange
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(testUserId.toString());
        when(rateLimitService.checkLimit(any(), any(), anyString(), any(RateLimitService.Tier.class), anyString()))
                .thenReturn(new RateLimitService.RateLimitResult(true, 100, 99, 3600));
        when(userPreferenceService.getPreferences(testUserId)).thenReturn(mockPreferences);
        when(stockService.getQuotes(anyList())).thenReturn(mockStockData);
        when(stockService.isMarketOpen()).thenReturn(false);

        // Act
        Response response = resource.getStocks(securityContext);

        // Assert
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        String cacheControl = response.getHeaderString("Cache-Control");
        assertEquals("max-age=3600", cacheControl); // 1 hour after hours
    }
}
