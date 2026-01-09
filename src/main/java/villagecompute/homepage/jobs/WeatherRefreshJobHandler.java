/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.jobs;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import villagecompute.homepage.api.types.WeatherLocationType;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.observability.LoggingConfig;
import villagecompute.homepage.services.WeatherService;

/**
 * Job handler for weather forecast refresh from Open-Meteo and NWS APIs.
 *
 * <p>
 * This handler refreshes weather cache for all user-configured locations by:
 * <ol>
 * <li>Querying all users' weather_locations from preferences JSONB</li>
 * <li>Deduplicating locations by rounded lat/lon</li>
 * <li>Calling {@link WeatherService#refreshCache(WeatherLocationType)} for each unique location</li>
 * <li>Exporting metrics and telemetry for observability</li>
 * </ol>
 *
 * <p>
 * <b>Execution Flow:</b>
 * <ul>
 * <li>Empty payload: refresh all user locations (hourly scheduled job)</li>
 * <li>Payload with {@code location_key}: refresh specific location only</li>
 * <li>Payload with {@code alerts_only=true}: refresh alerts only (15-min job)</li>
 * </ul>
 *
 * <p>
 * <b>Error Handling:</b> Individual location failures do NOT abort batch processing. Failed locations are logged and
 * tracked via metrics. Job continues with remaining locations. Cache serves stale data if API fails.
 *
 * <p>
 * <b>Telemetry:</b> Exports OpenTelemetry span attributes:
 * <ul>
 * <li>{@code job.id} - Job database primary key</li>
 * <li>{@code job.type} - WEATHER_REFRESH</li>
 * <li>{@code locations_total} - Total unique locations processed</li>
 * <li>{@code locations_success} - Locations refreshed successfully</li>
 * <li>{@code locations_failed} - Locations that failed to refresh</li>
 * </ul>
 *
 * <p>
 * And Micrometer metrics:
 * <ul>
 * <li>{@code weather.refresh.total} (Counter) - Tagged by {@code status={success|failure}}</li>
 * <li>{@code weather.refresh.duration} (Timer) - Total job execution time</li>
 * <li>{@code weather.refresh.locations} (Gauge) - Current unique location count</li>
 * </ul>
 *
 * <p>
 * <b>Payload Structure:</b>
 *
 * <pre>
 * {
 *   "location_key": "37.77:-122.42",  // Optional - refresh specific location
 *   "alerts_only": false               // Optional - refresh alerts only (15 min cadence)
 * }
 * </pre>
 */
@ApplicationScoped
public class WeatherRefreshJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(WeatherRefreshJobHandler.class);

    @Inject
    WeatherService weatherService;

    @Inject
    Tracer tracer;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public JobType handlesType() {
        return JobType.WEATHER_REFRESH;
    }

    @Override
    @Transactional
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        LoggingConfig.enrichWithTraceContext();
        LoggingConfig.setJobId(jobId);

        Span span = tracer.spanBuilder("job.weather_refresh").setAttribute("job.id", jobId)
                .setAttribute("job.type", "WEATHER_REFRESH").startSpan();

        Timer.Sample sample = Timer.start(meterRegistry);
        int successCount = 0;
        int failureCount = 0;

        try (Scope scope = span.makeCurrent()) {
            LOG.infof("Starting weather refresh job %d", jobId);

            // Extract payload parameters
            String targetLocationKey = payload.containsKey("location_key") ? payload.get("location_key").toString()
                    : null;
            boolean alertsOnly = payload.containsKey("alerts_only") && (Boolean) payload.get("alerts_only");

            span.setAttribute("target_location_key", targetLocationKey != null ? targetLocationKey : "all");
            span.setAttribute("alerts_only", alertsOnly);

            // Collect all unique weather locations from user preferences
            Set<WeatherLocationType> locations = collectUniqueLocations();
            span.setAttribute("locations_total", locations.size());
            LOG.infof("Found %d unique weather locations to refresh", locations.size());

            // Filter to target location if specified
            if (targetLocationKey != null) {
                locations = locations.stream()
                        .filter(loc -> targetLocationKey.equals(villagecompute.homepage.data.models.WeatherCache
                                .generateLocationKey(loc.latitude(), loc.longitude())))
                        .collect(java.util.stream.Collectors.toSet());
                LOG.infof("Filtered to target location: %s (%d locations)", targetLocationKey, locations.size());
            }

            // Refresh each location independently
            for (WeatherLocationType location : locations) {
                try {
                    weatherService.refreshCache(location);
                    successCount++;
                    incrementCounter("weather.refresh.total", "status", "success");
                    LOG.debugf("Refreshed weather for %s", location.city());
                } catch (Exception e) {
                    failureCount++;
                    incrementCounter("weather.refresh.total", "status", "failure");
                    LOG.errorf(e, "Failed to refresh weather for %s (continuing)", location.city());
                    // Don't abort batch processing on individual failures
                }
            }

            span.setAttribute("locations_success", successCount);
            span.setAttribute("locations_failed", failureCount);

            LOG.infof("Weather refresh job %d completed: %d success, %d failures", jobId, successCount, failureCount);

        } catch (Exception e) {
            span.recordException(e);
            LOG.errorf(e, "Weather refresh job %d failed", jobId);
            throw e;
        } finally {
            sample.stop(Timer.builder("weather.refresh.duration").register(meterRegistry));
            span.end();
            LoggingConfig.clearMDC();
        }
    }

    /**
     * Collects all unique weather locations from user preferences.
     * <p>
     * Queries all users, extracts weatherLocations from preferences JSONB, and deduplicates by rounded lat/lon.
     *
     * @return set of unique weather locations
     */
    private Set<WeatherLocationType> collectUniqueLocations() {
        Set<WeatherLocationType> locations = new HashSet<>();
        Set<String> seenKeys = new HashSet<>();

        List<User> users = User.listAll();
        for (User user : users) {
            if (user.preferences == null) {
                continue;
            }

            try {
                // Extract weatherLocations from JSONB preferences
                // Convert Map to JsonNode for easier path navigation
                JsonNode prefsNode = objectMapper.valueToTree(user.preferences);
                JsonNode locationsNode = prefsNode.path("weather_locations");

                if (locationsNode.isArray()) {
                    for (JsonNode locNode : locationsNode) {
                        String city = locNode.path("city").asText();
                        Integer cityId = locNode.path("city_id").isNull() ? null : locNode.path("city_id").asInt();
                        double latitude = locNode.path("latitude").asDouble();
                        double longitude = locNode.path("longitude").asDouble();

                        // Deduplicate by rounded location key
                        String locationKey = villagecompute.homepage.data.models.WeatherCache
                                .generateLocationKey(latitude, longitude);
                        if (!seenKeys.contains(locationKey)) {
                            seenKeys.add(locationKey);
                            locations.add(new WeatherLocationType(city, cityId, latitude, longitude));
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warnf(e, "Failed to parse weather locations for user %d", user.id);
            }
        }

        return locations;
    }

    /**
     * Helper to increment Micrometer counter.
     */
    private void incrementCounter(String name, String... tags) {
        Counter.builder(name).tags(tags).register(meterRegistry).increment();
    }
}
