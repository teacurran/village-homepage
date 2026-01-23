package villagecompute.homepage.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.ImpersonationAudit;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.observability.LoggingConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central authentication and identity management service.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>Anonymous cookie issuance ({@code vu_anon_id})</li>
 * <li>Rate-limited bootstrap guard for the first {@code super_admin}</li>
 * <li>JWT session token generation for bootstrap workflow</li>
 * <li>Delegated OAuth login redirects for Google, Facebook, and Apple</li>
 * </ul>
 *
 * <p>
 * The service intentionally centralizes Policy P1/P9 controls so REST resources remain thin. Future iterations will
 * extend this facade once database-backed user entities exist.
 */
@ApplicationScoped
public class AuthIdentityService {

    private static final Logger LOG = Logger.getLogger(AuthIdentityService.class);

    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("google", "facebook", "apple");

    @ConfigProperty(
            name = "villagecompute.auth.cookie.name",
            defaultValue = "vu_anon_id")
    String anonymousCookieName;

    @ConfigProperty(
            name = "villagecompute.auth.cookie.max-age",
            defaultValue = "31536000")
    int cookieMaxAge;

    @ConfigProperty(
            name = "villagecompute.auth.cookie.secure",
            defaultValue = "true")
    boolean cookieSecure;

    @ConfigProperty(
            name = "villagecompute.auth.cookie.http-only",
            defaultValue = "true")
    boolean cookieHttpOnly;

    @ConfigProperty(
            name = "villagecompute.auth.cookie.same-site",
            defaultValue = "Lax")
    String cookieSameSite;

    @ConfigProperty(
            name = "villagecompute.auth.cookie.domain")
    Optional<String> cookieDomain;

    @ConfigProperty(
            name = "villagecompute.auth.bootstrap.token")
    Optional<String> bootstrapToken;

    @ConfigProperty(
            name = "villagecompute.auth.bootstrap.admin-exists",
            defaultValue = "false")
    boolean adminExistsConfigured;

    @ConfigProperty(
            name = "villagecompute.auth.jwt.issuer",
            defaultValue = "https://homepage.villagecompute.com")
    String jwtIssuer;

    @ConfigProperty(
            name = "villagecompute.auth.jwt.audience",
            defaultValue = "village-homepage")
    String jwtAudience;

    @ConfigProperty(
            name = "villagecompute.auth.jwt.secret",
            defaultValue = "local-dev-secret-change-me")
    String jwtSecret;

    @ConfigProperty(
            name = "villagecompute.auth.jwt.expiration-minutes",
            defaultValue = "60")
    long jwtExpirationMinutes;

    @ConfigProperty(
            name = "villagecompute.auth.rate-limit.bootstrap.max-requests",
            defaultValue = "5")
    int bootstrapMaxRequests;

    @ConfigProperty(
            name = "villagecompute.auth.rate-limit.bootstrap.window-seconds",
            defaultValue = "3600")
    long bootstrapWindowSeconds;

    @ConfigProperty(
            name = "villagecompute.auth.rate-limit.login.max-requests",
            defaultValue = "20")
    int loginMaxRequests;

    @ConfigProperty(
            name = "villagecompute.auth.rate-limit.login.window-seconds",
            defaultValue = "60")
    long loginWindowSeconds;

    private final AtomicBoolean superuserProvisioned = new AtomicBoolean();
    private RateLimitService.RateLimitRule bootstrapRule;
    private RateLimitService.RateLimitRule loginRule;

    @Inject
    RateLimitService rateLimitService;

    @Inject
    Tracer tracer;

    @Inject
    ObjectMapper objectMapper;

    @PostConstruct
    void init() {
        superuserProvisioned.set(adminExistsConfigured);
        bootstrapRule = RateLimitService.RateLimitRule.of("bootstrap", bootstrapMaxRequests,
                Duration.ofSeconds(bootstrapWindowSeconds));
        loginRule = RateLimitService.RateLimitRule.of("auth-login", loginMaxRequests,
                Duration.ofSeconds(loginWindowSeconds));
    }

    /**
     * Issues a secure anonymous cookie (Policy P9).
     */
    public NewCookie issueAnonymousCookie() {
        Span span = tracer.spanBuilder("auth.issue_anonymous_cookie").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();

            String anonymousId = UUID.randomUUID().toString();
            LoggingConfig.setAnonId(anonymousId);
            LoggingConfig.setRequestOrigin("/api/auth/anonymous");

            NewCookie.Builder builder = new NewCookie.Builder(anonymousCookieName).value(anonymousId).path("/")
                    .maxAge(cookieMaxAge).secure(cookieSecure).httpOnly(cookieHttpOnly)
                    .sameSite(mapSameSite(cookieSameSite));
            cookieDomain.filter(s -> !s.isBlank()).ifPresent(builder::domain);

            NewCookie cookie = builder.build();
            span.addEvent("cookie.issued", Attributes.of(AttributeKey.stringKey("cookie.name"), anonymousCookieName,
                    AttributeKey.stringKey("cookie.id"), anonymousId));
            return cookie;
        } finally {
            LoggingConfig.clearMDC();
            span.end();
        }
    }

    /**
     * Validates bootstrap token with rate-limiting enforcement.
     */
    public BootstrapValidationResult validateBootstrapToken(String token, String ipAddress) {
        Span span = tracer.spanBuilder("auth.validate_bootstrap_token").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            LoggingConfig.setRequestOrigin("/api/auth/bootstrap");

            String bucket = "bootstrap:" + ipAddress;
            if (!rateLimitService.check(bucket, bootstrapRule)) {
                rateLimitService.recordViolation(bucket, "/api/auth/bootstrap", null, ipAddress);
                span.addEvent("bootstrap.rate_limited");
                return BootstrapValidationResult.RATE_LIMITED;
            }

            if (bootstrapToken.isEmpty()) {
                LOG.error("Bootstrap token not configured (missing BOOTSTRAP_TOKEN env var)");
                span.addEvent("bootstrap.token_not_configured");
                return BootstrapValidationResult.TOKEN_NOT_CONFIGURED;
            }

            if (superuserProvisioned.get()) {
                LOG.warn("Bootstrap attempt blocked - admin already exists");
                span.addEvent("bootstrap.admin_exists");
                return BootstrapValidationResult.ADMIN_EXISTS;
            }

            if (!Objects.equals(bootstrapToken.get(), token)) {
                LOG.warnf("Invalid bootstrap token attempt from %s", ipAddress);
                span.addEvent("bootstrap.invalid_token");
                return BootstrapValidationResult.INVALID_TOKEN;
            }

            span.addEvent("bootstrap.token_valid");
            return BootstrapValidationResult.SUCCESS;
        } finally {
            LoggingConfig.clearMDC();
            span.end();
        }
    }

    /**
     * Creates a JWT session for an existing authenticated user.
     *
     * @param user
     *            the authenticated user
     * @return bootstrap session with JWT token and expiration
     */
    public BootstrapSession createSessionForUser(User user) {
        Objects.requireNonNull(user, "user is required");

        if (user.isAnonymous) {
            throw new IllegalArgumentException("Cannot create session for anonymous user");
        }

        String subject = user.id.toString();
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(jwtExpirationMinutes, ChronoUnit.MINUTES);
        String jwt = generateJwt(subject, user.email, user.oauthProvider, issuedAt, expiresAt);

        LOG.infof("Created JWT session for user: email=%s, provider=%s, userId=%s", user.email, user.oauthProvider,
                user.id);
        return new BootstrapSession(jwt, expiresAt, user.email, user.oauthProvider);
    }

    /**
     * Finalizes bootstrap flow by creating the first super admin user, minting a JWT session token, and marking the
     * superuser as provisioned.
     */
    public BootstrapSession createSuperuser(String email, String provider, String providerUserId) {
        Objects.requireNonNull(email, "email is required");
        Objects.requireNonNull(provider, "provider is required");
        Objects.requireNonNull(providerUserId, "provider user id is required");

        String normalizedProvider = provider.toLowerCase();
        if (!isProviderSupported(normalizedProvider)) {
            throw new IllegalArgumentException("Unsupported provider: " + provider);
        }

        Span span = tracer.spanBuilder("auth.create_superuser").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            LoggingConfig.setRequestOrigin("bootstrap.superuser_creation");

            if (!superuserProvisioned.compareAndSet(false, true)) {
                LOG.warn("createSuperuser invoked but admin already exists");
                throw new IllegalStateException("Superuser already provisioned");
            }

            // Create the first super admin user in database
            User superuser = User.createAuthenticated(email, normalizedProvider, providerUserId, "Super Admin", null);
            superuser.adminRole = User.ROLE_SUPER_ADMIN;
            superuser.adminRoleGrantedAt = Instant.now();
            // adminRoleGrantedBy is null for bootstrap user (self-granted)
            QuarkusTransaction.requiringNew().run(() -> superuser.persist());

            String subject = superuser.id.toString();
            Instant issuedAt = Instant.now();
            Instant expiresAt = issuedAt.plus(jwtExpirationMinutes, ChronoUnit.MINUTES);
            String jwt = generateJwt(subject, email, normalizedProvider, issuedAt, expiresAt);

            LOG.infof("Bootstrap superuser created for %s via %s with id=%s", email, normalizedProvider, superuser.id);
            span.addEvent("superuser.created",
                    Attributes.of(AttributeKey.stringKey("email"), email, AttributeKey.stringKey("provider"),
                            normalizedProvider, AttributeKey.stringKey("user_id"), superuser.id.toString()));
            return new BootstrapSession(jwt, expiresAt, email, normalizedProvider);
        } finally {
            LoggingConfig.clearMDC();
            span.end();
        }
    }

    /**
     * Ensures authentication initiation is rate-limited per provider/IP combination.
     */
    public boolean checkLoginRateLimit(String provider, String ipAddress) {
        String normalizedProvider = provider.toLowerCase();
        String bucket = "login:" + normalizedProvider + ":" + ipAddress;
        boolean allowed = rateLimitService.check(bucket, loginRule);
        if (!allowed) {
            rateLimitService.recordViolation(bucket, "/api/auth/login/" + normalizedProvider, null, ipAddress);
        }
        return allowed;
    }

    /**
     * Builds redirect URI for Quarkus OIDC handler. The handler expects {@code /q/oidc/login?tenant=<provider>}.
     */
    public URI buildLoginRedirectUri(String provider, UriInfo uriInfo) {
        String normalizedProvider = provider.toLowerCase();
        if (!isProviderSupported(normalizedProvider)) {
            throw new IllegalArgumentException("Unsupported provider: " + provider);
        }
        return UriBuilder.fromUri(uriInfo.getBaseUri()).path("q").path("oidc").path("login")
                .queryParam("tenant", normalizedProvider).build();
    }

    public boolean isProviderSupported(String provider) {
        return provider != null && SUPPORTED_PROVIDERS.contains(provider.toLowerCase());
    }

    private String generateJwt(String subject, String email, String provider, Instant issuedAt, Instant expiresAt) {
        try {
            Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
            Map<String, Object> payload = Map.of("sub", subject, "email", email, "provider", provider, "roles",
                    Set.of("super_admin"), "iss", jwtIssuer, "aud", jwtAudience, "iat", issuedAt.getEpochSecond(),
                    "exp", expiresAt.getEpochSecond());

            String encodedHeader = base64Url(objectMapper.writeValueAsBytes(header));
            String encodedPayload = base64Url(objectMapper.writeValueAsBytes(payload));
            String signingInput = encodedHeader + "." + encodedPayload;

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = base64Url(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
            return signingInput + "." + signature;
        } catch (JsonProcessingException | NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to sign JWT for bootstrap session", e);
        }
    }

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private NewCookie.SameSite mapSameSite(String sameSite) {
        if (sameSite == null) {
            return NewCookie.SameSite.LAX;
        }
        return switch (sameSite.trim().toLowerCase()) {
            case "strict" -> NewCookie.SameSite.STRICT;
            case "none" -> NewCookie.SameSite.NONE;
            default -> NewCookie.SameSite.LAX;
        };
    }

    /**
     * Assigns an admin role to a user.
     *
     * @param userId
     *            the user to assign the role to
     * @param role
     *            the role to assign (super_admin, support, ops, read_only)
     * @param grantedBy
     *            the user ID who is granting the role
     * @throws IllegalArgumentException
     *             if user not found or role is invalid
     */
    public void assignRole(UUID userId, String role, UUID grantedBy) {
        Objects.requireNonNull(userId, "userId is required");
        Objects.requireNonNull(role, "role is required");
        Objects.requireNonNull(grantedBy, "grantedBy is required");

        // Validate role
        if (!Set.of(User.ROLE_SUPER_ADMIN, User.ROLE_SUPPORT, User.ROLE_OPS, User.ROLE_READ_ONLY).contains(role)) {
            throw new IllegalArgumentException("Invalid role: " + role);
        }

        QuarkusTransaction.requiringNew().run(() -> {
            User user = User.findById(userId);
            if (user == null || user.deletedAt != null) {
                throw new IllegalArgumentException("User not found: " + userId);
            }

            user.adminRole = role;
            user.adminRoleGrantedBy = grantedBy;
            user.adminRoleGrantedAt = Instant.now();
            user.updatedAt = Instant.now();
            user.persist();

            LOG.infof("Assigned role %s to user %s by %s", role, userId, grantedBy);
        });
    }

    /**
     * Revokes admin role from a user.
     *
     * @param userId
     *            the user to revoke the role from
     * @param revokedBy
     *            the user ID who is revoking the role
     * @throws IllegalArgumentException
     *             if user not found
     */
    public void revokeRole(UUID userId, UUID revokedBy) {
        Objects.requireNonNull(userId, "userId is required");
        Objects.requireNonNull(revokedBy, "revokedBy is required");

        QuarkusTransaction.requiringNew().run(() -> {
            User user = User.findById(userId);
            if (user == null || user.deletedAt != null) {
                throw new IllegalArgumentException("User not found: " + userId);
            }

            String previousRole = user.adminRole;
            user.adminRole = null;
            user.adminRoleGrantedBy = null;
            user.adminRoleGrantedAt = null;
            user.updatedAt = Instant.now();
            user.persist();

            LOG.infof("Revoked role %s from user %s by %s", previousRole, userId, revokedBy);
        });
    }

    /**
     * Gets the admin role for a user.
     *
     * @param userId
     *            the user ID
     * @return Optional containing the role if user exists and has an admin role
     */
    public Optional<String> getUserRole(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }

        User user = User.findById(userId);
        if (user == null || user.deletedAt != null) {
            return Optional.empty();
        }

        return Optional.ofNullable(user.adminRole);
    }

    /**
     * Checks if a user has a specific admin role.
     *
     * @param userId
     *            the user ID
     * @param role
     *            the role to check
     * @return true if user has the specified role, false otherwise
     */
    public boolean hasRole(UUID userId, String role) {
        if (userId == null || role == null) {
            return false;
        }

        User user = User.findById(userId);
        if (user == null || user.deletedAt != null) {
            return false;
        }

        return role.equals(user.adminRole);
    }

    /**
     * Starts an impersonation session and logs it to the audit table.
     *
     * @param impersonatorId
     *            the user ID performing the impersonation (must have admin role)
     * @param targetUserId
     *            the user ID being impersonated
     * @param ipAddress
     *            IP address of the impersonator
     * @param userAgent
     *            user agent string of the impersonator
     * @return the created impersonation audit entry
     * @throws IllegalArgumentException
     *             if impersonator is not an admin or target user not found
     */
    public ImpersonationAudit startImpersonation(UUID impersonatorId, UUID targetUserId, String ipAddress,
            String userAgent) {
        Objects.requireNonNull(impersonatorId, "impersonatorId is required");
        Objects.requireNonNull(targetUserId, "targetUserId is required");

        // Validate impersonator has admin role
        User impersonator = User.findById(impersonatorId);
        if (impersonator == null || !impersonator.isAdmin()) {
            throw new IllegalArgumentException("Impersonator must have an admin role. User: " + impersonatorId);
        }

        // Validate target user exists
        User target = User.findById(targetUserId);
        if (target == null || target.deletedAt != null) {
            throw new IllegalArgumentException("Target user not found: " + targetUserId);
        }

        // Check for existing active session
        Optional<ImpersonationAudit> existingSession = ImpersonationAudit.findActiveSession(impersonatorId,
                targetUserId);
        if (existingSession.isPresent()) {
            LOG.warnf("Active impersonation session already exists: %s", existingSession.get().id);
            return existingSession.get();
        }

        return ImpersonationAudit.startSession(impersonatorId, targetUserId, ipAddress, userAgent);
    }

    /**
     * Ends an impersonation session.
     *
     * @param impersonatorId
     *            the user ID performing the impersonation
     * @param targetUserId
     *            the user ID being impersonated
     * @throws IllegalArgumentException
     *             if no active session found
     */
    public void endImpersonation(UUID impersonatorId, UUID targetUserId) {
        Objects.requireNonNull(impersonatorId, "impersonatorId is required");
        Objects.requireNonNull(targetUserId, "targetUserId is required");

        Optional<ImpersonationAudit> activeSession = ImpersonationAudit.findActiveSession(impersonatorId, targetUserId);
        if (activeSession.isEmpty()) {
            throw new IllegalArgumentException("No active impersonation session found for impersonator="
                    + impersonatorId + ", target=" + targetUserId);
        }

        activeSession.get().endSession();
    }

    /**
     * Gets the impersonation history for a user (either as impersonator or target).
     *
     * @param userId
     *            the user ID
     * @param asImpersonator
     *            if true, get sessions where user was the impersonator; if false, where user was the target
     * @return list of impersonation audit entries
     */
    public List<ImpersonationAudit> getImpersonationHistory(UUID userId, boolean asImpersonator) {
        Objects.requireNonNull(userId, "userId is required");

        if (asImpersonator) {
            return ImpersonationAudit.findByImpersonator(userId);
        } else {
            return ImpersonationAudit.findByTarget(userId);
        }
    }

    public enum BootstrapValidationResult {
        SUCCESS, ADMIN_EXISTS, INVALID_TOKEN, TOKEN_NOT_CONFIGURED, RATE_LIMITED
    }

    public record BootstrapSession(String jwt, Instant expiresAt, String email, String provider) {
    }
}
