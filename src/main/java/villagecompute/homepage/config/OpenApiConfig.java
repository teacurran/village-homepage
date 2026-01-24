package villagecompute.homepage.config;

import org.eclipse.microprofile.openapi.annotations.Components;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeIn;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.info.Contact;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.info.License;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.servers.Server;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.core.Application;

/**
 * OpenAPI 3.0 configuration for Village Homepage API.
 *
 * <p>
 * Defines API metadata, security schemes, and endpoint groupings via tags.
 *
 * <p>
 * Task I6.T9: Generate comprehensive OpenAPI 3.0 documentation for all REST endpoints.
 *
 * @see <a href="https://github.com/eclipse/microprofile-open-api">MicroProfile OpenAPI Spec</a>
 */
@OpenAPIDefinition(
        info = @Info(
                title = "Village Homepage API",
                version = "1.0.0",
                description = """
                        Customizable homepage portal API with widgets, marketplace classifieds, and curated web directory.

                        ## Features
                        - **Homepage Widgets**: News, weather, stocks, social feeds, custom RSS
                        - **Marketplace**: Craigslist-style classifieds with geographic filtering
                        - **Good Sites Directory**: Reddit-style web directory with voting
                        - **Authentication**: Google/Facebook/Apple OAuth + anonymous sessions
                        - **Admin Tools**: Content moderation, feature flags, rate limits

                        ## Authentication
                        Most endpoints require a JWT bearer token obtained via OAuth login.
                        Anonymous users get a cookie-based session for personalization.

                        ## Rate Limiting
                        All endpoints are rate-limited. Limits vary by user tier and action type.
                        429 responses include `Retry-After` header with cooldown period.
                        """,
                contact = @Contact(
                        name = "Village Compute",
                        email = "tcurran@villagecompute.com",
                        url = "https://villagecompute.com"),
                license = @License(
                        name = "Proprietary")),
        servers = {@Server(
                url = "https://homepage.villagecompute.com",
                description = "Production"),
                @Server(
                        url = "https://homepage-beta.villagecompute.com",
                        description = "Beta"),
                @Server(
                        url = "http://localhost:8080",
                        description = "Local Development")},
        tags = {@Tag(
                name = "Authentication",
                description = "OAuth login flows, session management, bootstrap admin creation"),
                @Tag(
                        name = "Marketplace",
                        description = "Craigslist-style classifieds: browse, search, CRUD, images, messaging"),
                @Tag(
                        name = "Directory",
                        description = "Curated web directory: categories, site submission, voting, screenshots"),
                @Tag(
                        name = "Widgets",
                        description = "Homepage widget management and data endpoints (news, weather, stocks)"),
                @Tag(
                        name = "Notifications",
                        description = "Notification preferences and delivery management"),
                @Tag(
                        name = "Social",
                        description = "Social media feed integration (Instagram, Facebook)"),
                @Tag(
                        name = "Profile",
                        description = "User profile and karma management"),
                @Tag(
                        name = "GDPR",
                        description = "Data export and deletion requests (GDPR/CCPA compliance)"),
                @Tag(
                        name = "Admin - Feature Flags",
                        description = "Feature flag configuration and rollout management"),
                @Tag(
                        name = "Admin - Rate Limits",
                        description = "Rate limit configuration and monitoring"),
                @Tag(
                        name = "Admin - Moderation",
                        description = "Content moderation queues and flagged content review"),
                @Tag(
                        name = "Admin - Users",
                        description = "User management, search, bans, impersonation"),
                @Tag(
                        name = "Admin - Categories",
                        description = "Marketplace and directory category management"),
                @Tag(
                        name = "Admin - Analytics",
                        description = "Usage analytics and AI budget monitoring"),
                @Tag(
                        name = "Admin - Payments",
                        description = "Payment transaction monitoring and Stripe integration"),
                @Tag(
                        name = "Admin - System",
                        description = "Delayed job queues, RSS feeds, import tools"),
                @Tag(
                        name = "Health",
                        description = "Health checks and readiness probes")},
        components = @Components(
                securitySchemes = {@SecurityScheme(
                        securitySchemeName = "bearerAuth",
                        type = SecuritySchemeType.HTTP,
                        scheme = "bearer",
                        bearerFormat = "JWT",
                        description = "JWT bearer token obtained via OAuth login. Required for authenticated endpoints."),
                        @SecurityScheme(
                                securitySchemeName = "anonymousCookie",
                                type = SecuritySchemeType.APIKEY,
                                apiKeyName = "vu_anon_id",
                                in = SecuritySchemeIn.COOKIE,
                                description = "Anonymous session cookie for personalization. Auto-created on first visit.")}))
public class OpenApiConfig extends Application {
    // Configuration via annotations only - no programmatic setup needed
}
