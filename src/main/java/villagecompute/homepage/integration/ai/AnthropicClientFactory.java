/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.integration.ai;

import java.time.Duration;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Factory for creating configured Anthropic chat model instances.
 *
 * <p>
 * This factory provides CDI producers for different Anthropic model configurations, enabling model selection based on
 * use case requirements:
 * <ul>
 * <li><b>Sonnet (default)</b>: Claude 3.5 Sonnet for high-accuracy tasks (tagging, categorization, fraud
 * detection)</li>
 * <li><b>Haiku</b>: Claude 3 Haiku for cost-optimized bulk operations (large feed processing)</li>
 * </ul>
 *
 * <p>
 * <b>Model Selection Strategy:</b>
 * <ul>
 * <li>Content tagging and categorization: Use default Sonnet model for accuracy</li>
 * <li>Bulk feed processing during off-peak: Use Haiku model to optimize costs (Policy P2/P10)</li>
 * <li>Fraud detection: Use Sonnet model for higher confidence</li>
 * </ul>
 *
 * <p>
 * <b>Configuration Properties:</b> Reads from application.yaml under {@code quarkus.langchain4j.anthropic.*}
 *
 * <p>
 * <b>Retry Configuration:</b> All models include retry logic with exponential backoff to handle transient failures (429
 * rate limits, 5xx errors).
 *
 * <p>
 * <b>Usage:</b>
 *
 * <pre>
 * // Default Sonnet model (recommended for most use cases)
 * &#64;Inject
 * ChatModel chatModel;
 *
 * // Explicit Haiku model for cost-optimized operations
 * &#64;Inject
 * &#64;Named("haiku")
 * ChatModel haikuModel;
 * </pre>
 *
 * @see villagecompute.homepage.config.AiConfig
 */
@ApplicationScoped
public class AnthropicClientFactory {

    @ConfigProperty(
            name = "quarkus.langchain4j.anthropic.api-key")
    String apiKey;

    @ConfigProperty(
            name = "quarkus.langchain4j.anthropic.chat-model.model-name",
            defaultValue = "claude-3-5-sonnet-20241022")
    String sonnetModelName;

    @ConfigProperty(
            name = "quarkus.langchain4j.anthropic.chat-model.temperature",
            defaultValue = "0.7")
    Double temperature;

    @ConfigProperty(
            name = "quarkus.langchain4j.anthropic.chat-model.max-tokens",
            defaultValue = "4096")
    Integer maxTokens;

    @ConfigProperty(
            name = "quarkus.langchain4j.anthropic.chat-model.timeout",
            defaultValue = "60s")
    Duration timeout;

    @ConfigProperty(
            name = "quarkus.langchain4j.anthropic.chat-model.max-retries",
            defaultValue = "3")
    Integer maxRetries;

    /**
     * Produces the default Claude Sonnet 3.5 model for high-accuracy AI operations.
     *
     * <p>
     * This is the primary model used for content tagging, categorization, and fraud detection. It provides the highest
     * accuracy for tasks where quality is more important than cost.
     *
     * @return configured Anthropic chat model (Sonnet)
     */
    @Produces
    @ApplicationScoped
    public ChatModel defaultChatModel() {
        return createModel(sonnetModelName, temperature, maxTokens, timeout, maxRetries);
    }

    /**
     * Produces a Claude Haiku model for cost-optimized bulk operations.
     *
     * <p>
     * This model is intended for processing large volumes of content where cost optimization is a priority. Use this
     * for bulk feed processing during off-peak hours to stay within budget constraints (Policy P2/P10).
     *
     * @return configured Anthropic chat model (Haiku)
     */
    @Produces
    @Named("haiku")
    @ApplicationScoped
    public ChatModel haikuChatModel() {
        // Haiku model uses lower temperature for more consistent results in bulk operations
        return createModel("claude-3-haiku-20240307", 0.3, maxTokens, timeout, maxRetries);
    }

    /**
     * Creates a configured Anthropic chat model with retry logic.
     *
     * @param modelName
     *            Anthropic model identifier (e.g., claude-3-5-sonnet-20241022)
     * @param temperature
     *            Sampling temperature (0.0 = deterministic, 1.0 = creative)
     * @param maxTokens
     *            Maximum tokens in response
     * @param timeout
     *            Request timeout duration
     * @param maxRetries
     *            Maximum retry attempts for transient failures
     * @return configured chat model instance
     */
    private ChatModel createModel(String modelName, Double temperature, Integer maxTokens, Duration timeout,
            Integer maxRetries) {
        return AnthropicChatModel.builder().apiKey(apiKey).modelName(modelName).temperature(temperature)
                .maxTokens(maxTokens).timeout(timeout).maxRetries(maxRetries).build();
    }
}
