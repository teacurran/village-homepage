/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.api.types;

/**
 * Cache statistics for AI result caching (Feature I4.T6).
 *
 * <p>
 * Tracks cache hit rate and total hits/misses for cost optimization monitoring. Target hit rate: &gt;50% per acceptance
 * criteria.
 *
 * @param hits
 *            total cache hits
 * @param misses
 *            total cache misses
 * @param total
 *            total cache requests (hits + misses)
 * @param hitRate
 *            cache hit rate percentage (0.0-100.0)
 */
public record CacheStatsType(long hits, long misses, long total, double hitRate) {

    /**
     * Checks if cache hit rate meets target (>50%).
     *
     * @return true if hit rate exceeds 50%
     */
    public boolean meetsTarget() {
        return hitRate > 50.0;
    }
}
