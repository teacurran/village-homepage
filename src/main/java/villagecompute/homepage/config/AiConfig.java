/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.config;

import io.quarkus.runtime.Startup;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Configuration class for LangChain4j AI integration with Anthropic Claude.
 *
 * <p>
 * This class performs startup validation to ensure the Anthropic API key is configured. The Quarkiverse LangChain4j
 * extension automatically provides CDI beans based on the configuration properties in application.yaml.
 *
 * <p>
 * <b>Configuration Properties:</b>
 * <ul>
 * <li>{@code quarkus.langchain4j.anthropic.api-key} - Anthropic API key (from ANTHROPIC_API_KEY env var)</li>
 * <li>{@code quarkus.langchain4j.anthropic.chat-model.model-name} - Model name (default:
 * claude-3-5-sonnet-20241022)</li>
 * <li>{@code quarkus.langchain4j.anthropic.chat-model.temperature} - Sampling temperature (default: 0.7)</li>
 * <li>{@code quarkus.langchain4j.anthropic.chat-model.max-tokens} - Max output tokens (default: 4096)</li>
 * <li>{@code quarkus.langchain4j.anthropic.chat-model.timeout} - Request timeout (default: 60s)</li>
 * <li>{@code quarkus.langchain4j.anthropic.chat-model.max-retries} - Retry attempts (default: 3)</li>
 * </ul>
 *
 * <p>
 * <b>Fail-Fast Validation:</b> Application startup will fail if the Anthropic API key is not configured.
 *
 * <p>
 * <b>Usage:</b> The Quarkiverse extension provides auto-configured beans that can be injected:
 *
 * <pre>
 * &#64;Inject
 * ChatModel chatModel;
 * </pre>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P2/P10 (AI Budget Control): Model selection supports cost optimization strategies</li>
 * </ul>
 *
 * @see villagecompute.homepage.services.AiTaggingService
 * @see villagecompute.homepage.services.AiCategorizationService
 */
@ApplicationScoped
@Startup
public class AiConfig {

    private static final Logger LOG = Logger.getLogger(AiConfig.class);

    @ConfigProperty(
            name = "quarkus.langchain4j.anthropic.api-key")
    String apiKey;

    @ConfigProperty(
            name = "quarkus.langchain4j.anthropic.chat-model.model-name",
            defaultValue = "claude-3-5-sonnet-20241022")
    String modelName;

    /**
     * Performs startup validation to ensure the Anthropic API key is configured.
     *
     * <p>
     * This method is called at application startup due to the {@link Startup} annotation combined with
     * {@link PostConstruct}. If the API key is missing or empty, the application will fail to start with a descriptive
     * error message.
     *
     * @throws AiConfigurationException
     *             if the Anthropic API key is not configured
     */
    @PostConstruct
    public void validateConfiguration() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            String errorMessage = "ANTHROPIC_API_KEY environment variable is not configured. "
                    + "AI services require a valid Anthropic API key to function. "
                    + "Please set the ANTHROPIC_API_KEY environment variable and restart the application.";
            LOG.fatal(errorMessage);
            throw new AiConfigurationException(errorMessage);
        }
        LOG.infof("LangChain4j configured with model: %s", modelName);
    }

    /**
     * Exception thrown when AI configuration is invalid or incomplete.
     */
    public static class AiConfigurationException extends RuntimeException {

        public AiConfigurationException(String message) {
            super(message);
        }

        public AiConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
