/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.jobs;

import java.util.Map;

import org.jboss.logging.Logger;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import villagecompute.homepage.services.DelayedJobService;

/**
 * Scheduler for weather forecast refresh jobs.
 *
 * <p>
 * Enqueues two types of weather refresh jobs:
 * <ul>
 * <li><b>Hourly forecast refresh:</b> Full weather data update every 1 hour</li>
 * <li><b>Alert refresh:</b> Severe weather alerts every 15 minutes (US locations only)</li>
 * </ul>
 *
 * <p>
 * Jobs are processed asynchronously by {@link WeatherRefreshJobHandler} with automatic retry on failure (5 retries with
 * exponential backoff per DEFAULT queue policy).
 *
 * @see WeatherRefreshJobHandler
 */
@ApplicationScoped
public class WeatherRefreshScheduler {

    private static final Logger LOG = Logger.getLogger(WeatherRefreshScheduler.class);

    @Inject
    DelayedJobService jobService;

    /**
     * Schedules full weather forecast refresh every hour.
     * <p>
     * Refreshes current conditions, 24-hour hourly forecast, and 7-day daily forecast for all user locations.
     */
    @Scheduled(
            every = "1h")
    void scheduleWeatherRefresh() {
        LOG.info("Enqueuing hourly weather forecast refresh job");
        jobService.enqueue(JobType.WEATHER_REFRESH, Map.of());
    }

    /**
     * Schedules severe weather alert refresh every 15 minutes.
     * <p>
     * Refreshes only alert data for faster updates on time-sensitive severe weather warnings. Only applies to US
     * locations using NWS provider.
     */
    @Scheduled(
            every = "15m")
    void scheduleAlertsRefresh() {
        LOG.info("Enqueuing weather alerts refresh job");
        jobService.enqueue(JobType.WEATHER_REFRESH, Map.of("alerts_only", true));
    }
}
