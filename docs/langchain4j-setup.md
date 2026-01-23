# LangChain4j Configuration Summary

## Overview

This document summarizes the LangChain4j configuration setup completed in task I4.T1.

## Configuration Properties

The application uses the Quarkiverse LangChain4j extension (version 1.5.0) with Anthropic Claude as the LLM provider.

### Property Namespace

**Important:** Configuration properties use the `quarkus.langchain4j.anthropic.*` namespace (NOT `villagecompute.langchain4j.anthropic.*`).

### Key Properties (application.yaml)

```yaml
quarkus:
  langchain4j:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY:}
      chat-model:
        model-name: claude-3-5-sonnet-20241022
        temperature: 0.7
        max-tokens: 4096
        timeout: 60s
        max-retries: 3
      log-requests: ${AI_LOG_REQUESTS:false}
      log-responses: ${AI_LOG_RESPONSES:false}
```

## Java API

### ChatModel Interface

The Quarkiverse extension provides a `dev.langchain4j.model.chat.ChatModel` bean that can be injected:

```java
import dev.langchain4j.model.chat.ChatModel;

@Inject
ChatModel chatModel;
```

**Note:** The interface is `ChatModel`, not `ChatLanguageModel`. The existing commented imports in `AiTaggingService` and `AiCategorizationService` reference the wrong interface name and will need to be corrected when uncommenting in task I4.T2.

### Startup Validation

The `AiConfig` class performs fail-fast validation at application startup:

- Checks if `ANTHROPIC_API_KEY` environment variable is configured
- Throws `AiConfigurationException` if API key is missing or empty
- Logs the configured model name at startup

## Maven Dependencies

The project uses the Quarkiverse LangChain4j BOM:

```xml
<dependency>
    <groupId>io.quarkiverse.langchain4j</groupId>
    <artifactId>quarkus-langchain4j-bom</artifactId>
    <version>1.5.0</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>

<dependency>
    <groupId>io.quarkiverse.langchain4j</groupId>
    <artifactId>quarkus-langchain4j-anthropic</artifactId>
</dependency>
```

This provides:
- `dev.langchain4j:langchain4j:1.9.1`
- `dev.langchain4j:langchain4j-anthropic:1.9.1`
- `dev.langchain4j:langchain4j-core:1.9.1`

## Files Created/Modified

### Created
- `src/main/java/villagecompute/homepage/config/AiConfig.java` - Startup validation and configuration documentation
- `src/test/java/villagecompute/homepage/config/AiConfigTest.java` - Unit tests for validation logic

### Modified
- `src/main/resources/application.yaml` - Added LangChain4j configuration under `quarkus.langchain4j` namespace

## Next Steps (Task I4.T2)

When implementing AI tagging services:

1. **Import the correct interface:**
   ```java
   import dev.langchain4j.model.chat.ChatModel;  // NOT ChatLanguageModel
   ```

2. **Inject the ChatModel bean:**
   ```java
   @Inject
   ChatModel chatModel;
   ```

3. **Use the chat method:**
   ```java
   String response = chatModel.chat(prompt);
   ```

## Testing

To test without real API calls:

1. **Set a fake API key in test profile:**
   ```java
   public Map<String, String> getConfigOverrides() {
       return Map.of("quarkus.langchain4j.anthropic.api-key", "sk-ant-test-key-for-testing");
   }
   ```

2. **Use WireMock** to mock Anthropic API endpoints (WireMock already available from I1.T5)

## Environment Variables

Required for production:
- `ANTHROPIC_API_KEY` - Anthropic API key (e.g., `sk-ant-api03-...`)

Optional:
- `AI_LOG_REQUESTS` - Enable request logging (default: false)
- `AI_LOG_RESPONSES` - Enable response logging (default: false)
