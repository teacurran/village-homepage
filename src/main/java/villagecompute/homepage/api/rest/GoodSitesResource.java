package villagecompute.homepage.api.rest;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.*;
import villagecompute.homepage.data.models.*;
import villagecompute.homepage.exceptions.ResourceNotFoundException;
import villagecompute.homepage.exceptions.ValidationException;
import villagecompute.homepage.services.DirectoryVotingService;
import villagecompute.homepage.services.RateLimitService;

import java.util.*;

/**
 * Public Good Sites directory browsing endpoints (Feature F13.2).
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>GET /good-sites – Directory homepage with root categories</li>
 * <li>GET /good-sites/{slug} – Category page with sites and voting</li>
 * <li>GET /good-sites/site/{id} – Site detail page</li>
 * <li>GET /good-sites/search – Search results</li>
 * <li>POST /api/good-sites/vote – Cast/update vote (authenticated)</li>
 * <li>DELETE /api/good-sites/vote/{siteCategoryId} – Remove vote (authenticated)</li>
 * </ul>
 *
 * <p>
 * Security:
 * <ul>
 * <li>Browsing endpoints (@GET) are public (PermitAll)</li>
 * <li>Voting endpoints (@POST/@DELETE) require authentication</li>
 * <li>Voting is rate-limited (50 votes/hour per user)</li>
 * </ul>
 *
 * <p>
 * Bubbling Logic: Sites with score ≥10 and rank ≤3 in child categories bubble up to parent categories with a badge
 * indicating source category.
 * </p>
 */
@Path("/good-sites")
public class GoodSitesResource {

    private static final Logger LOG = Logger.getLogger(GoodSitesResource.class);
    private static final int PAGE_SIZE = 50;
    private static final int BUBBLE_SCORE_THRESHOLD = 10;
    private static final int BUBBLE_RANK_THRESHOLD = 3;

    @Inject
    DirectoryVotingService votingService;

    @Inject
    RateLimitService rateLimitService;

    @Context
    SecurityIdentity securityIdentity;

    /**
     * Type-safe Qute templates for Good Sites pages.
     */
    @CheckedTemplate(
            requireTypeSafeExpressions = false)
    public static class Templates {
        public static native TemplateInstance index(DirectoryHomeData data);

        public static native TemplateInstance category(CategoryPageData data);

        public static native TemplateInstance site(SiteDetailData data);

        public static native TemplateInstance search(SearchResultsData data);
    }

    /**
     * Directory homepage with root categories and featured sites.
     *
     * <p>
     * Route: GET /good-sites
     * </p>
     *
     * <p>
     * Example:
     * <a href="https://homepage.villagecompute.com/good-sites">https://homepage.villagecompute.com/good-sites</a>
     * </p>
     *
     * @return HTML page with category grid
     */
    @GET
    @PermitAll
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance homepage() {
        LOG.info("Rendering Good Sites homepage");

        // Get root categories
        List<DirectoryCategory> rootCategories = DirectoryCategory.findRootCategories();
        List<DirectoryCategoryType> categoryTypes = rootCategories.stream().map(DirectoryCategoryType::fromEntity)
                .toList();

        // Get popular sites (top 10 by score across all categories)
        List<CategorySiteType> popularSites = getPopularSites(10);

        DirectoryHomeType homeData = new DirectoryHomeType(categoryTypes, popularSites);

        DirectoryHomeData templateData = new DirectoryHomeData(homeData, getCurrentUserIdOptional());

        return Templates.index(templateData);
    }

    /**
     * Category page with sites, subcategories, and voting controls.
     *
     * <p>
     * Route: GET /good-sites/{slug}
     * </p>
     *
     * <p>
     * Example: <a href=
     * "https://homepage.villagecompute.com/good-sites/computers">https://homepage.villagecompute.com/good-sites/computers</a>
     * </p>
     *
     * @param slug
     *            Category URL slug
     * @param page
     *            Page number (1-indexed, default 1)
     * @return HTML page with category sites
     */
    @GET
    @Path("/{slug}")
    @PermitAll
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance categoryPage(@PathParam("slug") String slug,
            @QueryParam("page") @DefaultValue("1") int page) {
        LOG.infof("Rendering category page: slug=%s, page=%d", slug, page);

        // Find category by slug
        DirectoryCategory category = DirectoryCategory.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + slug));

        // Get direct sites in this category (with pagination)
        List<DirectorySiteCategory> directSiteCategories = DirectorySiteCategory.findApprovedInCategory(category.id);

        // Paginate direct sites
        int totalSites = directSiteCategories.size();
        int offset = (page - 1) * PAGE_SIZE;
        int toIndex = Math.min(offset + PAGE_SIZE, totalSites);

        // Handle case where offset is beyond total sites (empty page)
        List<DirectorySiteCategory> paginatedSiteCategories;
        if (offset >= totalSites) {
            paginatedSiteCategories = List.of();
        } else {
            paginatedSiteCategories = directSiteCategories.subList(offset, toIndex);
        }

        // Fetch site entities for paginated results
        List<CategorySiteType> directSites = new ArrayList<>();
        for (DirectorySiteCategory sc : paginatedSiteCategories) {
            DirectorySite site = DirectorySite.findById(sc.siteId);
            if (site != null && !site.isDead) {
                directSites.add(CategorySiteType.fromEntities(sc, site));
            }
        }

        // Get bubbled sites from child categories
        List<CategorySiteType> bubbledSites = getBubbledSites(category.id);

        // Get user's vote states (if authenticated)
        Map<UUID, Short> userVotes = getUserVoteStates(directSites, bubbledSites);

        // Build pagination metadata
        int totalPages = (int) Math.ceil((double) totalSites / PAGE_SIZE);

        CategoryViewType viewData = new CategoryViewType(DirectoryCategoryType.fromEntity(category), directSites,
                bubbledSites, userVotes, totalSites, page, PAGE_SIZE, totalPages);

        // Get subcategories for navigation
        List<DirectoryCategory> subcategories = DirectoryCategory.findByParentId(category.id);
        List<DirectoryCategoryType> subcategoryTypes = subcategories.stream().map(DirectoryCategoryType::fromEntity)
                .toList();

        // Build breadcrumb trail
        List<DirectoryCategoryType> breadcrumbs = buildBreadcrumbs(category);

        CategoryPageData templateData = new CategoryPageData(viewData, subcategoryTypes, breadcrumbs,
                getCurrentUserIdOptional(), isAuthenticated());

        return Templates.category(templateData);
    }

    /**
     * Site detail page showing metadata and all category memberships.
     *
     * <p>
     * Route: GET /good-sites/site/{id}
     * </p>
     *
     * @param id
     *            Site UUID
     * @return HTML page with site details
     */
    @GET
    @Path("/site/{id}")
    @PermitAll
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance siteDetail(@PathParam("id") UUID id) {
        LOG.infof("Rendering site detail: id=%s", id);

        // Find site
        DirectorySite site = DirectorySite.findById(id);
        if (site == null) {
            throw new ResourceNotFoundException("Site not found: " + id);
        }

        // Get all category memberships for this site
        List<DirectorySiteCategory> siteCategories = DirectorySiteCategory.findBySiteId(id);

        // Build category membership list
        List<SiteDetailType.SiteCategoryMembership> categories = new ArrayList<>();
        for (DirectorySiteCategory sc : siteCategories) {
            if ("approved".equals(sc.status)) {
                DirectoryCategory category = DirectoryCategory.findById(sc.categoryId);
                if (category != null) {
                    categories.add(new SiteDetailType.SiteCategoryMembership(DirectoryCategoryType.fromEntity(category),
                            sc.id, sc.score, sc.upvotes, sc.downvotes, sc.rankInCategory, sc.status));
                }
            }
        }

        // Get user's vote states
        Map<UUID, Short> userVotes = new HashMap<>();
        if (isAuthenticated()) {
            UUID userId = getCurrentUserId();
            for (DirectorySiteCategory sc : siteCategories) {
                Optional<Short> vote = votingService.getUserVote(sc.id, userId);
                vote.ifPresent(v -> userVotes.put(sc.id, v));
            }
        }

        SiteDetailType detailData = new SiteDetailType(DirectorySiteType.fromEntity(site), categories, userVotes);

        SiteDetailData templateData = new SiteDetailData(detailData, getCurrentUserIdOptional(), isAuthenticated());

        return Templates.site(templateData);
    }

    /**
     * Search results page.
     *
     * <p>
     * Route: GET /good-sites/search?q=query
     * </p>
     *
     * @param query
     *            Search query
     * @param categoryFilter
     *            Optional category ID filter
     * @return HTML page with search results
     */
    @GET
    @Path("/search")
    @PermitAll
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance search(@QueryParam("q") String query,
            @QueryParam("category") @DefaultValue("") String categoryFilter) {
        LOG.infof("Rendering search results: query=%s, category=%s", query, categoryFilter);

        if (query == null || query.isBlank()) {
            return Templates.search(new SearchResultsData(List.of(), "", null, getCurrentUserIdOptional()));
        }

        // Simple full-text search on title and description
        String searchPattern = "%" + query.toLowerCase() + "%";
        List<DirectorySite> sites = DirectorySite.list(
                "LOWER(title) LIKE ?1 OR LOWER(description) LIKE ?1 AND status = 'approved' AND isDead = false ORDER BY title",
                searchPattern);

        // Limit to top 50 results
        if (sites.size() > 50) {
            sites = sites.subList(0, 50);
        }

        // Convert to CategorySiteType (use first category membership for each site)
        List<CategorySiteType> results = new ArrayList<>();
        for (DirectorySite site : sites) {
            List<DirectorySiteCategory> siteCategories = DirectorySiteCategory.findBySiteId(site.id);
            if (!siteCategories.isEmpty()) {
                // Use first approved category
                for (DirectorySiteCategory sc : siteCategories) {
                    if ("approved".equals(sc.status)) {
                        results.add(CategorySiteType.fromEntities(sc, site));
                        break;
                    }
                }
            }
        }

        SearchResultsData templateData = new SearchResultsData(results, query,
                categoryFilter.isEmpty() ? null : categoryFilter, getCurrentUserIdOptional());

        return Templates.search(templateData);
    }

    /**
     * Casts or updates a vote on a site-category membership (authenticated).
     *
     * <p>
     * Route: POST /api/good-sites/vote
     * </p>
     *
     * <p>
     * Request body:
     *
     * <pre>{@code
     * {
     *   "site_category_id": "770e8400-e29b-41d4-a716-446655440020",
     *   "vote": 1
     * }
     * }</pre>
     *
     * @param request
     *            Vote request
     * @return 200 OK with updated vote state
     */
    @POST
    @Path("/api/vote")
    @RolesAllowed({"user", "super_admin", "support", "ops"})
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response castVote(VoteRequestType request) {
        UUID userId = getCurrentUserId();

        LOG.infof("User %s casting vote on site-category %s: %+d", userId, request.siteCategoryId(), request.vote());

        // Rate limit check (50 votes per hour)
        // Note: userId is UUID but RateLimitService expects Long, so pass null for now
        RateLimitService.RateLimitResult rateLimitResult = rateLimitService.checkLimit(null, null, "directory_vote",
                RateLimitService.Tier.LOGGED_IN, "/api/good-sites/vote");

        if (!rateLimitResult.allowed()) {
            throw new ValidationException("Vote rate limit exceeded. Please try again later.");
        }

        try {
            votingService.castVote(request.siteCategoryId(), userId, request.vote());

            // Get updated vote aggregates
            DirectorySiteCategory sc = DirectorySiteCategory.findById(request.siteCategoryId());
            if (sc == null) {
                throw new ResourceNotFoundException("Site-category not found");
            }

            VoteResponseType response = new VoteResponseType(sc.id, sc.score, sc.upvotes, sc.downvotes, request.vote());

            return Response.ok(response).build();

        } catch (ResourceNotFoundException | ValidationException e) {
            LOG.warnf("Vote failed: %s", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    /**
     * Removes a user's vote from a site-category membership (authenticated).
     *
     * <p>
     * Route: DELETE /api/good-sites/vote/{siteCategoryId}
     * </p>
     *
     * @param siteCategoryId
     *            Site-category membership ID
     * @return 204 No Content on success
     */
    @DELETE
    @Path("/api/vote/{siteCategoryId}")
    @RolesAllowed({"user", "super_admin", "support", "ops"})
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response removeVote(@PathParam("siteCategoryId") UUID siteCategoryId) {
        UUID userId = getCurrentUserId();

        LOG.infof("User %s removing vote from site-category %s", userId, siteCategoryId);

        try {
            votingService.removeVote(siteCategoryId, userId);
            return Response.noContent().build();

        } catch (ResourceNotFoundException e) {
            LOG.warnf("Vote removal failed: %s", e.getMessage());
            return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    /**
     * Gets top-ranked sites from child categories that meet bubbling criteria.
     *
     * <p>
     * Bubbling rules:
     * <ul>
     * <li>Score ≥ 10</li>
     * <li>Rank in category ≤ 3</li>
     * </ul>
     *
     * @param parentCategoryId
     *            Parent category ID
     * @return List of bubbled sites with source category name
     */
    private List<CategorySiteType> getBubbledSites(UUID parentCategoryId) {
        List<CategorySiteType> bubbledSites = new ArrayList<>();

        // Get child categories
        List<DirectoryCategory> childCategories = DirectoryCategory.findByParentId(parentCategoryId);

        for (DirectoryCategory child : childCategories) {
            // Find top sites in child category that meet bubbling criteria
            List<DirectorySiteCategory> topSites = DirectorySiteCategory.find(
                    "categoryId = ?1 AND status = 'approved' AND score >= ?2 AND rankInCategory <= ?3 ORDER BY score DESC",
                    child.id, BUBBLE_SCORE_THRESHOLD, BUBBLE_RANK_THRESHOLD).list();

            for (DirectorySiteCategory sc : topSites) {
                DirectorySite site = DirectorySite.findById(sc.siteId);
                if (site != null && !site.isDead) {
                    bubbledSites.add(CategorySiteType.fromEntitiesBubbled(sc, site, child.name));
                }
            }
        }

        // Sort bubbled sites by score descending
        bubbledSites.sort((a, b) -> Integer.compare(b.score(), a.score()));

        return bubbledSites;
    }

    /**
     * Gets user's vote states for a list of sites.
     *
     * @param directSites
     *            Direct sites in category
     * @param bubbledSites
     *            Bubbled sites from child categories
     * @return Map of site-category ID to vote value (+1/-1)
     */
    private Map<UUID, Short> getUserVoteStates(List<CategorySiteType> directSites,
            List<CategorySiteType> bubbledSites) {
        if (!isAuthenticated()) {
            return Map.of();
        }

        UUID userId = getCurrentUserId();
        Map<UUID, Short> votes = new HashMap<>();

        // Get votes for direct sites
        for (CategorySiteType site : directSites) {
            Optional<Short> vote = votingService.getUserVote(site.siteCategoryId(), userId);
            vote.ifPresent(v -> votes.put(site.siteCategoryId(), v));
        }

        // Get votes for bubbled sites
        for (CategorySiteType site : bubbledSites) {
            Optional<Short> vote = votingService.getUserVote(site.siteCategoryId(), userId);
            vote.ifPresent(v -> votes.put(site.siteCategoryId(), v));
        }

        return votes;
    }

    /**
     * Gets popular sites across all categories (for homepage).
     *
     * @param limit
     *            Maximum number of sites to return
     * @return List of popular sites
     */
    private List<CategorySiteType> getPopularSites(int limit) {
        List<DirectorySiteCategory> topSites = DirectorySiteCategory
                .find("status = 'approved' ORDER BY score DESC, createdAt DESC").page(0, limit).list();

        List<CategorySiteType> popularSites = new ArrayList<>();
        for (DirectorySiteCategory sc : topSites) {
            DirectorySite site = DirectorySite.findById(sc.siteId);
            if (site != null && !site.isDead) {
                popularSites.add(CategorySiteType.fromEntities(sc, site));
            }
        }

        return popularSites;
    }

    /**
     * Builds breadcrumb trail for a category (parent hierarchy).
     *
     * @param category
     *            Current category
     * @return List of categories from root to current (in order)
     */
    private List<DirectoryCategoryType> buildBreadcrumbs(DirectoryCategory category) {
        List<DirectoryCategoryType> breadcrumbs = new ArrayList<>();
        DirectoryCategory current = category;

        // Traverse up the parent chain
        while (current != null) {
            breadcrumbs.add(0, DirectoryCategoryType.fromEntity(current)); // Insert at beginning
            current = current.parentId != null ? DirectoryCategory.findById(current.parentId) : null;
        }

        return breadcrumbs;
    }

    /**
     * Checks if current user is authenticated.
     */
    private boolean isAuthenticated() {
        return securityIdentity != null && !securityIdentity.isAnonymous();
    }

    /**
     * Gets current user ID (throws if not authenticated).
     */
    private UUID getCurrentUserId() {
        if (!isAuthenticated()) {
            throw new UnauthorizedException("Authentication required");
        }
        String userIdStr = securityIdentity.getPrincipal().getName();
        return UUID.fromString(userIdStr);
    }

    /**
     * Gets current user ID as Optional (empty if anonymous).
     */
    private Optional<UUID> getCurrentUserIdOptional() {
        if (!isAuthenticated()) {
            return Optional.empty();
        }
        return Optional.of(getCurrentUserId());
    }

    /**
     * Template data records for Qute templates.
     */
    public record DirectoryHomeData(DirectoryHomeType homeData, Optional<UUID> userId) {
    }

    public record CategoryPageData(CategoryViewType viewData, List<DirectoryCategoryType> subcategories,
            List<DirectoryCategoryType> breadcrumbs, Optional<UUID> userId, boolean isAuthenticated) {
    }

    public record SiteDetailData(SiteDetailType detailData, Optional<UUID> userId, boolean isAuthenticated) {
    }

    public record SearchResultsData(List<CategorySiteType> results, String query, String categoryFilter,
            Optional<UUID> userId) {
    }

    /**
     * Vote request type.
     */
    public record VoteRequestType(UUID siteCategoryId, short vote) {
    }

    /**
     * Vote response type.
     */
    public record VoteResponseType(UUID siteCategoryId, int score, int upvotes, int downvotes, short userVote) {
    }

    /**
     * Error response DTO.
     */
    private record ErrorResponse(String message) {
    }
}
