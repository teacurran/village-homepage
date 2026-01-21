package villagecompute.homepage.api.rest.profile;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.*;
import villagecompute.homepage.data.models.FeedItem;
import villagecompute.homepage.data.models.ProfileCuratedArticle;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.data.models.UserProfile;
import villagecompute.homepage.exceptions.DuplicateResourceException;
import villagecompute.homepage.exceptions.ResourceNotFoundException;
import villagecompute.homepage.exceptions.ValidationException;
import villagecompute.homepage.services.ProfileService;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Public and authenticated profile endpoints (Feature F11).
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>GET /u/{username} – Public profile page (HTML)</li>
 * <li>GET /api/profiles/{id} – Profile data (JSON)</li>
 * <li>POST /api/profiles – Create profile (authenticated)</li>
 * <li>PUT /api/profiles/{id} – Update profile (authenticated, owner only)</li>
 * <li>DELETE /api/profiles/{id} – Soft delete profile (authenticated, owner only)</li>
 * <li>PUT /api/profiles/{id}/publish – Publish profile (authenticated, owner only)</li>
 * <li>PUT /api/profiles/{id}/unpublish – Unpublish profile (authenticated, owner only)</li>
 * <li>POST /api/profiles/{id}/articles – Add curated article (authenticated, owner only)</li>
 * <li>PUT /api/profiles/{id}/articles/{articleId} – Update article customization (authenticated, owner only)</li>
 * <li>DELETE /api/profiles/{id}/articles/{articleId} – Remove article (authenticated, owner only)</li>
 * </ul>
 *
 * <p>
 * Security:
 * <ul>
 * <li>Public profile page is accessible to all (published profiles only)</li>
 * <li>Profile creation requires authentication (no anonymous)</li>
 * <li>Profile updates/deletes require owner authentication</li>
 * <li>Admins can view all profiles</li>
 * </ul>
 *
 * <p>
 * Policy P1 (GDPR/CCPA): Soft delete with 90-day retention
 * </p>
 * <p>
 * Policy P9: Anonymous users cannot create profiles
 * </p>
 */
@Path("/")
public class ProfileResource {

    private static final Logger LOG = Logger.getLogger(ProfileResource.class);

    @Inject
    ProfileService profileService;

    @Context
    SecurityIdentity securityIdentity;

    @Context
    UriInfo uriInfo;

    /**
     * Type-safe Qute templates for profile pages.
     */
    @CheckedTemplate(
            requireTypeSafeExpressions = false)
    public static class Templates {
        public static native TemplateInstance profile(ProfilePageData data);
    }

    /**
     * Public profile page at /u/{username}.
     *
     * <p>
     * Returns 404 if profile not found or unpublished. Increments view count on each access.
     * </p>
     *
     * @param username
     *            profile username
     * @return HTML profile page
     */
    @GET
    @Path("/u/{username}")
    @PermitAll
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance getPublicProfile(@PathParam("username") String username) {
        LOG.infof("Accessing public profile: %s", username);

        try {
            UserProfile profile = profileService.getProfileByUsername(username);

            // Return 404 if profile is not published
            if (!profile.isPublished) {
                LOG.infof("Profile %s is not published, returning 404", username);
                throw new ResourceNotFoundException("Profile not found: " + username);
            }

            // Get curated articles
            List<ProfileCuratedArticle> articles = profileService.listArticles(profile.id);

            // Extract session/user info for view tracking
            UUID userId = getCurrentUserId();
            String sessionId = securityIdentity.isAnonymous() ? "anon-" + System.currentTimeMillis()
                    : securityIdentity.getPrincipal().getName();
            String ipAddress = uriInfo.getRequestUri().getHost();
            String userAgent = "unknown"; // TODO: Extract from request headers

            // Increment view count
            profileService.incrementViewCount(profile.id, userId, sessionId, ipAddress, userAgent);

            // Build page data
            ProfilePageData data = new ProfilePageData(toType(profile),
                    articles.stream().map(this::toArticleType).collect(Collectors.toList()), getCurrentUser());

            return Templates.profile(data);

        } catch (ResourceNotFoundException e) {
            throw new NotFoundException("Profile not found: " + username);
        }
    }

    /**
     * Get profile by ID (JSON).
     *
     * <p>
     * Returns profile data if:
     * <ul>
     * <li>Profile is published (public access)</li>
     * <li>Requester is profile owner (draft access)</li>
     * <li>Requester is admin (all access)</li>
     * </ul>
     * </p>
     *
     * @param id
     *            profile UUID
     * @return profile data
     */
    @GET
    @Path("/api/profiles/{id}")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    public Response getProfile(@PathParam("id") UUID id) {
        LOG.infof("Getting profile: %s", id);

        try {
            UserProfile profile = profileService.getProfile(id);

            // Access control
            UUID currentUserId = getCurrentUserId();
            boolean isOwner = currentUserId != null && currentUserId.equals(profile.userId);
            boolean isAdmin = isAdmin();

            // Only published profiles are public
            if (!profile.isPublished && !isOwner && !isAdmin) {
                throw new ForbiddenException("Access denied to unpublished profile");
            }

            return Response.ok(toType(profile)).build();

        } catch (ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Profile not found")).build();
        }
    }

    /**
     * Create a new profile.
     *
     * <p>
     * Creates profile in draft state. Requires authentication. Anonymous users cannot create profiles per Policy P9.
     * </p>
     *
     * @param request
     *            create request with username
     * @return created profile with Location header
     */
    @POST
    @Path("/api/profiles")
    @RolesAllowed({"USER", User.ROLE_SUPER_ADMIN, User.ROLE_SUPPORT})
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response createProfile(@Valid CreateProfileRequestType request) {
        UUID userId = getCurrentUserId();
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity(Map.of("error", "Authentication required"))
                    .build();
        }

        LOG.infof("Creating profile for user %s with username: %s", userId, request.username());

        try {
            UserProfile profile = profileService.createProfile(userId, request.username());

            URI location = uriInfo.getAbsolutePathBuilder().path(profile.id.toString()).build();

            return Response.created(location).entity(toType(profile)).build();

        } catch (ValidationException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();

        } catch (DuplicateResourceException e) {
            return Response.status(Response.Status.CONFLICT).entity(Map.of("error", e.getMessage())).build();
        }
    }

    /**
     * Update profile fields.
     *
     * <p>
     * Updates profile fields. Owner only.
     * </p>
     *
     * @param id
     *            profile UUID
     * @param request
     *            update request
     * @return updated profile
     */
    @PUT
    @Path("/api/profiles/{id}")
    @RolesAllowed({"USER", User.ROLE_SUPER_ADMIN, User.ROLE_SUPPORT})
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response updateProfile(@PathParam("id") UUID id, @Valid UpdateProfileRequestType request) {
        LOG.infof("Updating profile: %s", id);

        try {
            UserProfile profile = profileService.getProfile(id);

            // Access control (owner or admin)
            if (!isOwnerOrAdmin(profile.userId)) {
                return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "Access denied")).build();
            }

            // Update fields
            UserProfile updated = profileService.updateProfile(id, request.displayName(), request.bio(),
                    request.locationText(), request.websiteUrl(), request.avatarUrl());

            // Update social links if provided
            if (request.socialLinks() != null) {
                updated = profileService.updateSocialLinks(id, request.socialLinks());
            }

            return Response.ok(toType(updated)).build();

        } catch (ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Profile not found")).build();

        } catch (ValidationException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }
    }

    /**
     * Publish profile.
     *
     * <p>
     * Makes profile publicly accessible at /u/{username}. Owner only.
     * </p>
     *
     * @param id
     *            profile UUID
     * @return published profile
     */
    @PUT
    @Path("/api/profiles/{id}/publish")
    @RolesAllowed({"USER", User.ROLE_SUPER_ADMIN, User.ROLE_SUPPORT})
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response publishProfile(@PathParam("id") UUID id) {
        LOG.infof("Publishing profile: %s", id);

        try {
            UserProfile profile = profileService.getProfile(id);

            if (!isOwnerOrAdmin(profile.userId)) {
                return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "Access denied")).build();
            }

            UserProfile published = profileService.publishProfile(id);
            return Response.ok(toType(published)).build();

        } catch (ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Profile not found")).build();
        }
    }

    /**
     * Unpublish profile.
     *
     * <p>
     * Returns profile to draft state (404 on access). Owner only.
     * </p>
     *
     * @param id
     *            profile UUID
     * @return unpublished profile
     */
    @PUT
    @Path("/api/profiles/{id}/unpublish")
    @RolesAllowed({"USER", User.ROLE_SUPER_ADMIN, User.ROLE_SUPPORT})
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response unpublishProfile(@PathParam("id") UUID id) {
        LOG.infof("Unpublishing profile: %s", id);

        try {
            UserProfile profile = profileService.getProfile(id);

            if (!isOwnerOrAdmin(profile.userId)) {
                return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "Access denied")).build();
            }

            UserProfile unpublished = profileService.unpublishProfile(id);
            return Response.ok(toType(unpublished)).build();

        } catch (ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Profile not found")).build();
        }
    }

    /**
     * Soft delete profile.
     *
     * <p>
     * Soft deletes profile with 90-day retention per Policy P1. Owner only.
     * </p>
     *
     * @param id
     *            profile UUID
     * @return no content
     */
    @DELETE
    @Path("/api/profiles/{id}")
    @RolesAllowed({"USER", User.ROLE_SUPER_ADMIN, User.ROLE_SUPPORT})
    @Transactional
    public Response deleteProfile(@PathParam("id") UUID id) {
        LOG.infof("Deleting profile: %s", id);

        try {
            UserProfile profile = profileService.getProfile(id);

            if (!isOwnerOrAdmin(profile.userId)) {
                return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "Access denied")).build();
            }

            profileService.deleteProfile(id);
            return Response.noContent().build();

        } catch (ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Profile not found")).build();
        }
    }

    /**
     * Add curated article to profile.
     *
     * @param id
     *            profile UUID
     * @param request
     *            article request
     * @return created article
     */
    @POST
    @Path("/api/profiles/{id}/articles")
    @RolesAllowed({"USER", User.ROLE_SUPER_ADMIN, User.ROLE_SUPPORT})
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response addArticle(@PathParam("id") UUID id, @Valid AddArticleRequestType request) {
        LOG.infof("Adding article to profile: %s", id);

        try {
            UserProfile profile = profileService.getProfile(id);

            if (!isOwnerOrAdmin(profile.userId)) {
                return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "Access denied")).build();
            }

            ProfileCuratedArticle article;
            if (request.feedItemId() != null) {
                article = profileService.addCuratedArticle(id, request.feedItemId(), request.originalUrl(),
                        request.originalTitle(), request.originalDescription(), request.originalImageUrl());
            } else {
                article = profileService.addManualArticle(id, request.originalUrl(), request.originalTitle(),
                        request.originalDescription(), request.originalImageUrl());
            }

            return Response.status(Response.Status.CREATED).entity(toArticleType(article)).build();

        } catch (ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Profile not found")).build();

        } catch (ValidationException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }
    }

    /**
     * Update article customization.
     *
     * @param id
     *            profile UUID
     * @param articleId
     *            article UUID
     * @param request
     *            customization request
     * @return updated article
     */
    @PUT
    @Path("/api/profiles/{id}/articles/{articleId}")
    @RolesAllowed({"USER", User.ROLE_SUPER_ADMIN, User.ROLE_SUPPORT})
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response updateArticle(@PathParam("id") UUID id, @PathParam("articleId") UUID articleId,
            @Valid UpdateArticleCustomizationType request) {

        LOG.infof("Updating article %s for profile: %s", articleId, id);

        try {
            UserProfile profile = profileService.getProfile(id);

            if (!isOwnerOrAdmin(profile.userId)) {
                return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "Access denied")).build();
            }

            ProfileCuratedArticle article = profileService.updateArticleCustomization(articleId,
                    request.customHeadline(), request.customBlurb(), request.customImageUrl());

            return Response.ok(toArticleType(article)).build();

        } catch (ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build();
        }
    }

    /**
     * Remove article from profile (deactivate).
     *
     * @param id
     *            profile UUID
     * @param articleId
     *            article UUID
     * @return no content
     */
    @DELETE
    @Path("/api/profiles/{id}/articles/{articleId}")
    @RolesAllowed({"USER", User.ROLE_SUPER_ADMIN, User.ROLE_SUPPORT})
    @Transactional
    public Response removeArticle(@PathParam("id") UUID id, @PathParam("articleId") UUID articleId) {
        LOG.infof("Removing article %s from profile: %s", articleId, id);

        try {
            UserProfile profile = profileService.getProfile(id);

            if (!isOwnerOrAdmin(profile.userId)) {
                return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "Access denied")).build();
            }

            profileService.removeArticle(articleId);
            return Response.noContent().build();

        } catch (ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Article not found")).build();
        }
    }

    /**
     * Assign article to template slot.
     *
     * <p>
     * Validates slot capacity and updates slot assignment for a curated article. Owner only.
     * </p>
     *
     * @param id
     *            profile UUID
     * @param articleId
     *            article UUID
     * @param request
     *            slot assignment request
     * @return updated article
     */
    @PUT
    @Path("/api/profiles/{id}/articles/{articleId}/slot")
    @RolesAllowed({"USER", User.ROLE_SUPER_ADMIN, User.ROLE_SUPPORT})
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response assignSlot(@PathParam("id") UUID id, @PathParam("articleId") UUID articleId,
            @Valid SlotAssignmentType request) {

        LOG.infof("Assigning article %s to slot for profile %s: template=%s, slot=%s", articleId, id,
                request.template(), request.slot());

        try {
            UserProfile profile = profileService.getProfile(id);

            if (!isOwnerOrAdmin(profile.userId)) {
                return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "Access denied")).build();
            }

            ProfileCuratedArticle article = profileService.assignArticleToSlot(articleId, id, request.template(),
                    request.toMap());

            return Response.ok(toArticleType(article)).build();

        } catch (ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build();

        } catch (ValidationException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }
    }

    /**
     * Get available feed items for curation (feed picker).
     *
     * <p>
     * Returns paginated list of recent feed items from RSS sources. Owner only.
     * </p>
     *
     * @param id
     *            profile UUID
     * @param offset
     *            pagination offset (default: 0)
     * @param limit
     *            page size (default: 20, max: 100)
     * @return list of feed items
     */
    @GET
    @Path("/api/profiles/{id}/feed-items")
    @RolesAllowed({"USER", User.ROLE_SUPER_ADMIN, User.ROLE_SUPPORT})
    @Produces(MediaType.APPLICATION_JSON)
    public Response getFeedItems(@PathParam("id") UUID id, @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("limit") @DefaultValue("20") int limit) {

        LOG.infof("Getting feed items for profile %s: offset=%d, limit=%d", id, offset, limit);

        try {
            UserProfile profile = profileService.getProfile(id);

            if (!isOwnerOrAdmin(profile.userId)) {
                return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "Access denied")).build();
            }

            // Validate pagination params
            if (offset < 0) {
                return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Offset must be >= 0"))
                        .build();
            }
            if (limit < 1 || limit > 100) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Limit must be between 1 and 100")).build();
            }

            // Query recent feed items
            List<FeedItem> items = FeedItem.findRecent(offset, limit);
            List<FeedItemType> feedItems = items.stream().map(FeedItemType::fromEntity).collect(Collectors.toList());

            return Response.ok(Map.of("items", feedItems, "offset", offset, "limit", limit, "total", items.size()))
                    .build();

        } catch (ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Profile not found")).build();
        }
    }

    /**
     * Get slot information for a template.
     *
     * <p>
     * Returns available slots and their capacities for a given template type. Owner only.
     * </p>
     *
     * @param id
     *            profile UUID
     * @param template
     *            template type (public_homepage, your_times, your_report)
     * @return slot information
     */
    @GET
    @Path("/api/profiles/{id}/slots")
    @RolesAllowed({"USER", User.ROLE_SUPER_ADMIN, User.ROLE_SUPPORT})
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSlotInfo(@PathParam("id") UUID id,
            @QueryParam("template") @DefaultValue("your_times") String template) {

        LOG.infof("Getting slot info for profile %s: template=%s", id, template);

        try {
            UserProfile profile = profileService.getProfile(id);

            if (!isOwnerOrAdmin(profile.userId)) {
                return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "Access denied")).build();
            }

            Map<String, Object> slotInfo = profileService.getSlotInfo(template);
            return Response.ok(slotInfo).build();

        } catch (ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Profile not found")).build();
        }
    }

    // Helper methods

    private UUID getCurrentUserId() {
        if (securityIdentity.isAnonymous()) {
            return null;
        }
        try {
            return UUID.fromString(securityIdentity.getPrincipal().getName());
        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid user ID in security context: %s", securityIdentity.getPrincipal().getName());
            return null;
        }
    }

    private User getCurrentUser() {
        UUID userId = getCurrentUserId();
        if (userId == null) {
            return null;
        }
        return User.findById(userId);
    }

    private boolean isAdmin() {
        return securityIdentity.hasRole(User.ROLE_SUPER_ADMIN) || securityIdentity.hasRole(User.ROLE_SUPPORT);
    }

    private boolean isOwnerOrAdmin(UUID ownerId) {
        UUID currentUserId = getCurrentUserId();
        return (currentUserId != null && currentUserId.equals(ownerId)) || isAdmin();
    }

    private UserProfileType toType(UserProfile profile) {
        return new UserProfileType(profile.id, profile.userId, profile.username, profile.displayName, profile.bio,
                profile.avatarUrl, profile.locationText, profile.websiteUrl, profile.socialLinks, profile.template,
                profile.templateConfig, profile.isPublished, profile.viewCount, profile.createdAt, profile.updatedAt);
    }

    private ProfileCuratedArticleType toArticleType(ProfileCuratedArticle article) {
        return new ProfileCuratedArticleType(article.id, article.profileId, article.feedItemId, article.originalUrl,
                article.originalTitle, article.originalDescription, article.originalImageUrl, article.customHeadline,
                article.customBlurb, article.customImageUrl, article.getEffectiveHeadline(),
                article.getEffectiveDescription(), article.getEffectiveImageUrl(), article.slotAssignment,
                article.isActive, article.createdAt, article.updatedAt);
    }

    /**
     * Profile page data for Qute template.
     */
    public record ProfilePageData(UserProfileType profile, List<ProfileCuratedArticleType> articles, User currentUser) {
    }
}
