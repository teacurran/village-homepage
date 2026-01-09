package villagecompute.homepage.api.rest.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.CreateRssSourceRequestType;
import villagecompute.homepage.api.types.FeedItemType;
import villagecompute.homepage.api.types.RssSourceType;
import villagecompute.homepage.api.types.UpdateRssSourceRequestType;
import villagecompute.homepage.data.models.FeedItem;
import villagecompute.homepage.data.models.RssSource;
import villagecompute.homepage.exceptions.DuplicateResourceException;
import villagecompute.homepage.services.FeedAggregationService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin REST endpoints for RSS feed source and item management.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>{@code GET /admin/api/feeds/sources} – list all RSS sources</li>
 * <li>{@code GET /admin/api/feeds/sources/{id}} – get single source</li>
 * <li>{@code POST /admin/api/feeds/sources} – create new system feed</li>
 * <li>{@code PATCH /admin/api/feeds/sources/{id}} – update source configuration</li>
 * <li>{@code DELETE /admin/api/feeds/sources/{id}} – delete source (cascade delete items/subscriptions)</li>
 * <li>{@code GET /admin/api/feeds/items} – list feed items with optional source filter</li>
 * <li>{@code GET /admin/api/feeds/items/{id}} – get single feed item</li>
 * </ul>
 *
 * <p>
 * <b>Security:</b> All endpoints secured with {@code @RolesAllowed("super_admin")} for admin-only access.
 *
 * <p>
 * <b>Validation:</b> JAX-RS {@code @Valid} annotation triggers constraint validation (URL format, refresh interval
 * range, name length).
 */
@Path("/admin/api/feeds")
@RolesAllowed("super_admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FeedAdminResource {

    private static final Logger LOG = Logger.getLogger(FeedAdminResource.class);

    @Inject
    FeedAggregationService feedService;

    /**
     * Lists all RSS sources.
     *
     * @return list of all sources with full configuration
     */
    @GET
    @Path("/sources")
    public Response listSources() {
        List<RssSource> sources = feedService.getAllSources();
        List<RssSourceType> response = sources.stream().map(RssSourceType::fromEntity).toList();
        return Response.ok(response).build();
    }

    /**
     * Retrieves a single RSS source by ID.
     *
     * @param id
     *            the source UUID
     * @return source configuration or 404 if not found
     */
    @GET
    @Path("/sources/{id}")
    public Response getSource(@PathParam("id") UUID id) {
        Optional<RssSource> source = feedService.getSourceById(id);
        if (source.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse("RSS source not found: " + id))
                    .build();
        }
        return Response.ok(RssSourceType.fromEntity(source.get())).build();
    }

    /**
     * Creates a new system RSS source (admin-only).
     *
     * <p>
     * Validates URL format and checks for duplicate URLs (returns 409 Conflict).
     *
     * @param request
     *            creation payload with required fields
     * @return created source configuration with 201 Created status
     */
    @POST
    @Path("/sources")
    public Response createSource(@Valid CreateRssSourceRequestType request) {
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("Request body required"))
                    .build();
        }

        try {
            int refreshInterval = request.refreshIntervalMinutes() != null ? request.refreshIntervalMinutes() : 60;
            RssSource created = feedService.createSystemSource(request.name(), request.url(), request.category(),
                    refreshInterval);

            LOG.infof("Created RSS source: id=%s, name=%s, url=%s", created.id, created.name, created.url);
            return Response.status(Response.Status.CREATED).entity(RssSourceType.fromEntity(created)).build();
        } catch (DuplicateResourceException e) {
            return Response.status(Response.Status.CONFLICT).entity(new ErrorResponse(e.getMessage())).build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to create RSS source: %s", request.url());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Failed to create RSS source")).build();
        }
    }

    /**
     * Updates an RSS source configuration.
     *
     * <p>
     * Supports partial updates via PATCH semantics. Null fields are ignored.
     *
     * @param id
     *            the source UUID to update
     * @param request
     *            update payload with optional fields
     * @return updated source configuration or 404 if not found
     */
    @PATCH
    @Path("/sources/{id}")
    public Response updateSource(@PathParam("id") UUID id, @Valid UpdateRssSourceRequestType request) {
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("Request body required"))
                    .build();
        }

        try {
            Optional<RssSource> updated = feedService.updateSource(id, request.name(), request.category(),
                    request.refreshIntervalMinutes(), request.isActive());

            if (updated.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(new ErrorResponse("RSS source not found: " + id)).build();
            }

            LOG.infof("Updated RSS source: id=%s", id);
            return Response.ok(RssSourceType.fromEntity(updated.get())).build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to update RSS source: %s", id);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Failed to update RSS source")).build();
        }
    }

    /**
     * Deletes an RSS source by ID.
     *
     * <p>
     * Cascade deletes all associated feed items and user subscriptions (PostgreSQL FK constraints).
     *
     * @param id
     *            the source UUID to delete
     * @return 204 No Content on success, 404 if not found
     */
    @DELETE
    @Path("/sources/{id}")
    public Response deleteSource(@PathParam("id") UUID id) {
        boolean deleted = feedService.deleteSource(id);
        if (!deleted) {
            return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse("RSS source not found: " + id))
                    .build();
        }

        LOG.infof("Deleted RSS source: id=%s", id);
        return Response.noContent().build();
    }

    /**
     * Lists feed items with optional source filter.
     *
     * @param sourceId
     *            optional source UUID to filter items
     * @param limit
     *            maximum number of items to return (default 100, max 500)
     * @return list of feed items
     */
    @GET
    @Path("/items")
    public Response listItems(@QueryParam("source_id") UUID sourceId, @QueryParam("limit") Integer limit) {
        int itemLimit = limit != null ? Math.min(limit, 500) : 100;

        List<FeedItem> items;
        if (sourceId != null) {
            items = feedService.getFeedItemsBySource(sourceId);
            // Manual limit for source-filtered results
            items = items.stream().limit(itemLimit).toList();
        } else {
            items = feedService.getRecentFeedItems(itemLimit);
        }

        List<FeedItemType> response = items.stream().map(FeedItemType::fromEntity).toList();
        return Response.ok(response).build();
    }

    /**
     * Retrieves a single feed item by ID.
     *
     * @param id
     *            the feed item UUID
     * @return feed item or 404 if not found
     */
    @GET
    @Path("/items/{id}")
    public Response getItem(@PathParam("id") UUID id) {
        Optional<FeedItem> item = feedService.getFeedItemById(id);
        if (item.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse("Feed item not found: " + id))
                    .build();
        }
        return Response.ok(FeedItemType.fromEntity(item.get())).build();
    }

    /**
     * Simple error response record for API errors.
     */
    public record ErrorResponse(String error) {
    }
}
