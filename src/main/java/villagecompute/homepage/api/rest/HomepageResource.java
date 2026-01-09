package villagecompute.homepage.api.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.LayoutWidgetType;
import villagecompute.homepage.api.types.ThemeType;
import villagecompute.homepage.api.types.UserPreferencesType;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.observability.LoggingConfig;
import villagecompute.homepage.services.AuthIdentityService;
import villagecompute.homepage.services.FeatureFlagService;
import villagecompute.homepage.services.UserPreferenceService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Homepage Experience Shell resource controller.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>Server-side rendering of the homepage with Qute templates</li>
 * <li>Support for both anonymous and authenticated users</li>
 * <li>Feature flag evaluation to gate widget rendering</li>
 * <li>Theme application via CSS tokens</li>
 * <li>Edit mode toggle for layout customization</li>
 * <li>React islands hydration via data-mount attributes</li>
 * </ul>
 *
 * <p>
 * <b>Anonymous Users:</b> Identified by {@code vu_anon_id} cookie. If the cookie is missing, a new one is issued.
 * Preferences are stored and retrieved using the anonymous session hash.
 *
 * <p>
 * <b>Authenticated Users:</b> Identified by JWT session or OAuth token. Preferences are loaded from the database using
 * the user ID.
 *
 * <p>
 * <b>Edit Mode:</b> Activated via {@code ?edit=true} query parameter. In edit mode, the GridstackEditor React component
 * is mounted to enable drag-and-drop layout customization.
 */
@Path("/")
public class HomepageResource {

    private static final Logger LOG = Logger.getLogger(HomepageResource.class);

    @Inject
    UserPreferenceService userPreferenceService;

    @Inject
    FeatureFlagService featureFlagService;

    @Inject
    AuthIdentityService authIdentityService;

    @Inject
    Tracer tracer;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(
            name = "villagecompute.auth.cookie.name",
            defaultValue = "vu_anon_id")
    String anonymousCookieName;

    /**
     * Type-safe Qute templates.
     */
    @CheckedTemplate(
            requireTypeSafeExpressions = false)
    public static class Templates {
        public static native TemplateInstance homepage(HomepageData data);
    }

    /**
     * Renders the homepage with user preferences and feature flags.
     *
     * @param anonCookie
     *            anonymous session cookie (null for authenticated users)
     * @param editMode
     *            edit mode query parameter (true to enable layout editing)
     * @param securityContext
     *            JAX-RS security context for authenticated user detection
     * @return Response with rendered HTML and optional Set-Cookie header
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response homepage(@CookieParam("vu_anon_id") String anonCookie, @QueryParam("edit") boolean editMode,
            @Context SecurityContext securityContext) {

        Span span = tracer.spanBuilder("homepage.render").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            LoggingConfig.setRequestOrigin("/");

            // Determine if user is authenticated
            boolean isAuthenticated = securityContext.getUserPrincipal() != null;
            UUID userId = null;
            String sessionHash = anonCookie;

            NewCookie cookieToSet = null;

            if (isAuthenticated) {
                // Extract user ID from security context (simplified for now)
                // In production, this would parse JWT or OAuth token
                String principalName = securityContext.getUserPrincipal().getName();
                Optional<User> userOpt = User.findByEmail(principalName);
                if (userOpt.isPresent()) {
                    userId = userOpt.get().id;
                    LoggingConfig.setUserId((long) userId.hashCode());
                    span.setAttribute("user_id", userId.toString());
                }
            } else {
                // Anonymous user flow
                if (anonCookie == null || anonCookie.isBlank()) {
                    // Issue new anonymous cookie
                    cookieToSet = authIdentityService.issueAnonymousCookie();
                    sessionHash = cookieToSet.getValue();
                    LoggingConfig.setAnonId(sessionHash);
                    span.setAttribute("anon_id", sessionHash);
                } else {
                    LoggingConfig.setAnonId(sessionHash);
                    span.setAttribute("anon_id", sessionHash);
                }
            }

            // Edit mode only for authenticated users
            boolean actualEditMode = editMode && isAuthenticated;

            span.setAttribute("is_authenticated", isAuthenticated);
            span.setAttribute("edit_mode", actualEditMode);

            // Load user preferences
            UserPreferencesType preferences;
            if (userId != null) {
                preferences = userPreferenceService.getPreferences(userId);
            } else {
                // For anonymous users, use defaults for now
                // In a complete implementation, we'd store preferences keyed by sessionHash
                preferences = UserPreferencesType.createDefault();
            }

            // Evaluate feature flags to filter layout
            boolean consentGranted = false; // TODO: Read from cookie consent banner state
            List<LayoutWidgetType> filteredLayout = filterLayoutByFeatureFlags(preferences.layout(), userId,
                    sessionHash, consentGranted);

            // Load manifest.json for hashed JavaScript bundle paths
            Map<String, String> manifest = loadManifest();
            String mountsScriptPath = manifest.getOrDefault("mounts", "/assets/js/mounts.js");

            // Serialize widget configs to JSON for React islands
            String widgetConfigsJson = serializeToJson(preferences.widgetConfigs());

            // Build template data
            HomepageData data = new HomepageData(isAuthenticated, actualEditMode, filteredLayout, preferences.theme(),
                    mountsScriptPath, widgetConfigsJson);

            TemplateInstance template = Templates.homepage(data);

            // Render template to string
            String renderedHtml = template.render();

            // Build response with rendered HTML
            Response.ResponseBuilder responseBuilder = Response.ok(renderedHtml, MediaType.TEXT_HTML);
            if (cookieToSet != null) {
                responseBuilder.cookie(cookieToSet);
            }

            LOG.infof("Rendered homepage for %s (widgets: %d, edit: %s)",
                    isAuthenticated ? "user " + userId : "anon " + sessionHash, filteredLayout.size(), actualEditMode);

            return responseBuilder.build();

        } finally {
            LoggingConfig.clearMDC();
            span.end();
        }
    }

    /**
     * Filters layout widgets based on feature flag evaluation.
     *
     * <p>
     * Widgets are excluded if their corresponding feature flag is disabled:
     * <ul>
     * <li>{@code stocks} widget requires {@code stocks_widget} flag</li>
     * <li>{@code social_feed} widget requires {@code social_integration} flag</li>
     * </ul>
     *
     * @param layout
     *            full layout from user preferences
     * @param userId
     *            authenticated user ID (null for anonymous)
     * @param sessionHash
     *            anonymous session hash (null for authenticated)
     * @param consentGranted
     *            whether user has consented to analytics
     * @return filtered layout with only enabled widgets
     */
    private List<LayoutWidgetType> filterLayoutByFeatureFlags(List<LayoutWidgetType> layout, UUID userId,
            String sessionHash, boolean consentGranted) {

        // Convert UUID to Long for feature flag evaluation
        // Use hashCode as stable numeric identifier
        Long userIdLong = userId != null ? (long) userId.hashCode() : null;

        return layout.stream().filter(widget -> {
            String flagKey = getFeatureFlagForWidget(widget.widgetType());
            if (flagKey == null) {
                return true; // No feature flag required, include widget
            }

            FeatureFlagService.EvaluationResult result = featureFlagService.evaluateFlag(flagKey, userIdLong,
                    sessionHash, consentGranted);
            return result.enabled();
        }).collect(Collectors.toList());
    }

    /**
     * Maps widget types to their corresponding feature flag keys.
     *
     * @param widgetType
     *            widget type identifier
     * @return feature flag key or null if no flag required
     */
    private String getFeatureFlagForWidget(String widgetType) {
        return switch (widgetType) {
            case "stocks" -> "stocks_widget";
            case "social_feed" -> "social_integration";
            default -> null;
        };
    }

    /**
     * Loads the esbuild manifest.json to resolve hashed JavaScript bundle paths.
     *
     * @return map of logical names to hashed paths
     */
    private Map<String, String> loadManifest() {
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("META-INF/resources/assets/js/manifest.json")) {
            if (is == null) {
                LOG.warn("manifest.json not found, using default paths");
                return Map.of("mounts", "/assets/js/mounts.js");
            }
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, String> manifest = objectMapper.readValue(json, Map.class);
            return manifest;
        } catch (IOException e) {
            LOG.warnf(e, "Failed to load manifest.json, using default paths");
            return Map.of("mounts", "/assets/js/mounts.js");
        }
    }

    /**
     * Serializes an object to JSON string for data-props attributes.
     *
     * @param obj
     *            object to serialize
     * @return JSON string or empty object on error
     */
    private String serializeToJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            LOG.warnf(e, "Failed to serialize object to JSON");
            return "{}";
        }
    }

    /**
     * Template data record for homepage rendering.
     *
     * @param isAuthenticated
     *            whether user is authenticated
     * @param editMode
     *            whether edit mode is active
     * @param layout
     *            filtered widget layout (feature flags applied)
     * @param theme
     *            theme preferences
     * @param mountsScriptPath
     *            path to hashed React islands bundle
     * @param widgetConfigsJson
     *            serialized widget configs for React props
     */
    public record HomepageData(boolean isAuthenticated, boolean editMode, List<LayoutWidgetType> layout,
            ThemeType theme, String mountsScriptPath, String widgetConfigsJson) {

        /**
         * Gets human-readable widget title for display.
         *
         * @param widgetType
         *            widget type identifier
         * @return display title
         */
        public String widgetTitle(String widgetType) {
            return switch (widgetType) {
                case "search_bar" -> "Search";
                case "news_feed" -> "News Feed";
                case "weather" -> "Weather";
                case "stocks" -> "Stocks";
                case "social_feed" -> "Social Feed";
                case "rss_feed" -> "RSS Feed";
                case "quick_links" -> "Quick Links";
                default -> widgetType;
            };
        }
    }
}
