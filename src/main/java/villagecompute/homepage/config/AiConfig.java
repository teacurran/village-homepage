/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.config;

import java.time.Duration;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;

import io.quarkus.runtime.Startup;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Configuration class for LangChain4j AI integration with Anthropic Claude.
 *
 * <p>
 * This class performs startup validation to ensure the Anthropic API key is configured. It produces two ChatModel beans
 * to support cost optimization per P2/P10 budget policy:
 * <ul>
 * <li><b>Sonnet</b> (claude-3-5-sonnet-20241022): High-accuracy model for fraud detection (Feature I4.T4)</li>
 * <li><b>Haiku</b> (claude-3-haiku-20240307): Cost-efficient model for bulk tagging/categorization (10x cheaper)</li>
 * </ul>
 *
 * <p>
 * <b>Configuration Properties:</b>
 * <ul>
 * <li>{@code quarkus.langchain4j.anthropic.api-key} - Anthropic API key (from ANTHROPIC_API_KEY env var)</li>
 * <li>{@code ai.model.sonnet.name} - Sonnet model name (default: claude-3-5-sonnet-20241022)</li>
 * <li>{@code ai.model.haiku.name} - Haiku model name (default: claude-3-haiku-20240307)</li>
 * <li>{@code ai.model.temperature} - Sampling temperature (default: 0.7)</li>
 * <li>{@code ai.model.max-tokens} - Max output tokens (default: 4096)</li>
 * <li>{@code ai.model.timeout-seconds} - Request timeout (default: 60)</li>
 * <li>{@code ai.model.max-retries} - Retry attempts (default: 3)</li>
 * </ul>
 *
 * <p>
 * <b>Usage:</b> Inject models by qualifier:
 *
 * <pre>
 * &#64;Inject
 * &#64;Named("sonnet")
 * ChatModel sonnetModel; // For high-accuracy tasks
 * &#64;Inject
 * &#64;Named("haiku")
 * ChatModel haikuModel; // For bulk processing
 * </pre>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P2/P10 (AI Budget Control): Dual-model strategy reduces costs by 10x for bulk operations</li>
 * </ul>
 *
 * @see villagecompute.homepage.services.AiTaggingService
 * @see villagecompute.homepage.services.AiCategorizationService
 * @see villagecompute.homepage.services.FraudDetectionService
 */
@ApplicationScoped
@Startup
public class AiConfig {

    private static final Logger LOG = Logger.getLogger(AiConfig.class);

    @ConfigProperty(
            name = "quarkus.langchain4j.anthropic.api-key")
    String apiKey;

    @ConfigProperty(
            name = "ai.model.sonnet.name",
            defaultValue = "claude-3-5-sonnet-20241022")
    String sonnetModelName;

    @ConfigProperty(
            name = "ai.model.haiku.name",
            defaultValue = "claude-3-haiku-20240307")
    String haikuModelName;

    @ConfigProperty(
            name = "ai.model.temperature",
            defaultValue = "0.7")
    double temperature;

    @ConfigProperty(
            name = "ai.model.max-tokens",
            defaultValue = "4096")
    int maxTokens;

    @ConfigProperty(
            name = "ai.model.timeout-seconds",
            defaultValue = "60")
    int timeoutSeconds;

    @ConfigProperty(
            name = "ai.model.max-retries",
            defaultValue = "3")
    int maxRetries;

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
        LOG.infof("LangChain4j configured with Sonnet: %s, Haiku: %s", sonnetModelName, haikuModelName);
    }

    /**
     * Produces Sonnet ChatModel bean for high-accuracy AI tasks (fraud detection).
     *
     * <p>
     * Sonnet is 10x more expensive than Haiku but provides higher accuracy. Use for:
     * <ul>
     * <li>Fraud detection (Feature I4.T4) - requires nuanced understanding of deceptive patterns</li>
     * <li>Complex categorization requiring semantic reasoning</li>
     * </ul>
     *
     * @return configured Sonnet ChatModel
     */
    @Produces
    @ApplicationScoped
    @Named("sonnet")
    public ChatModel createSonnetModel() {
        LOG.infof("Creating Sonnet ChatModel: model=%s, temperature=%.2f, maxTokens=%d, timeout=%ds, maxRetries=%d",
                sonnetModelName, temperature, maxTokens, timeoutSeconds, maxRetries);

        return AnthropicChatModel.builder().apiKey(apiKey).modelName(sonnetModelName).temperature(temperature)
                .maxTokens(maxTokens).timeout(Duration.ofSeconds(timeoutSeconds)).maxRetries(maxRetries)
                .logRequests(false).logResponses(false).build();
    }

    /**
     * Produces Haiku ChatModel bean for cost-efficient bulk AI tasks (tagging, categorization).
     *
     * <p>
     * Haiku is 10x cheaper than Sonnet while maintaining sufficient accuracy for bulk operations. Use for:
     * <ul>
     * <li>Feed item tagging (Feature I4.T2) - topic extraction, summary generation</li>
     * <li>Listing categorization (Feature I4.T3) - category assignment based on title/description</li>
     * <li>Any batch processing where cost >> accuracy</li>
     * </ul>
     *
     * @return configured Haiku ChatModel
     */
    @Produces
    @ApplicationScoped
    @Named("haiku")
    public ChatModel createHaikuModel() {
        LOG.infof("Creating Haiku ChatModel: model=%s, temperature=%.2f, maxTokens=%d, timeout=%ds, maxRetries=%d",
                haikuModelName, temperature, maxTokens, timeoutSeconds, maxRetries);

        return AnthropicChatModel.builder().apiKey(apiKey).modelName(haikuModelName).temperature(temperature)
                .maxTokens(maxTokens).timeout(Duration.ofSeconds(timeoutSeconds)).maxRetries(maxRetries)
                .logRequests(false).logResponses(false).build();
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
