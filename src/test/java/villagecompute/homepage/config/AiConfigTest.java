/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import villagecompute.homepage.config.AiConfig.AiConfigurationException;

/**
 * Unit tests for {@link AiConfig} validation logic.
 *
 * <p>
 * These tests verify that:
 * <ul>
 * <li>Validation succeeds when API key is configured</li>
 * <li>Validation fails when API key is missing or empty</li>
 * </ul>
 *
 * <p>
 * <b>Note:</b> These are lightweight unit tests that don't require Quarkus context or database setup. Integration tests
 * for ChatModel bean injection are handled separately in full application context tests.
 */
class AiConfigTest {

    /**
     * Verifies that validation succeeds when a valid API key is provided.
     */
    @Test
    void testValidationSucceedsWithValidApiKey() {
        AiConfig config = new AiConfig();
        config.apiKey = "sk-ant-test-12345678";
        config.sonnetModelName = "claude-3-5-sonnet-20241022";
        config.haikuModelName = "claude-3-haiku-20240307";

        assertDoesNotThrow(() -> {
            config.validateConfiguration();
        }, "Validation should succeed with a valid API key");
    }

    /**
     * Verifies that validation fails when API key is null.
     */
    @Test
    void testValidationFailsWithNullApiKey() {
        AiConfig config = new AiConfig();
        config.apiKey = null;
        config.sonnetModelName = "claude-3-5-sonnet-20241022";
        config.haikuModelName = "claude-3-haiku-20240307";

        assertThrows(AiConfigurationException.class, () -> {
            config.validateConfiguration();
        }, "Validation should fail when API key is null");
    }

    /**
     * Verifies that validation fails when API key is empty.
     */
    @Test
    void testValidationFailsWithEmptyApiKey() {
        AiConfig config = new AiConfig();
        config.apiKey = "";
        config.sonnetModelName = "claude-3-5-sonnet-20241022";
        config.haikuModelName = "claude-3-haiku-20240307";

        assertThrows(AiConfigurationException.class, () -> {
            config.validateConfiguration();
        }, "Validation should fail when API key is empty");
    }

    /**
     * Verifies that validation fails when API key contains only whitespace.
     */
    @Test
    void testValidationFailsWithWhitespaceApiKey() {
        AiConfig config = new AiConfig();
        config.apiKey = "   ";
        config.sonnetModelName = "claude-3-5-sonnet-20241022";
        config.haikuModelName = "claude-3-haiku-20240307";

        assertThrows(AiConfigurationException.class, () -> {
            config.validateConfiguration();
        }, "Validation should fail when API key contains only whitespace");
    }
}
