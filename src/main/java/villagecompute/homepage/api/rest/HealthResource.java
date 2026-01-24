package villagecompute.homepage.api.rest;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/health")
@Tag(
        name = "Health",
        description = "Health check operations")
public class HealthResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Health check",
            description = "Check if the application is running")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Application is healthy",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = HealthResponse.class)))})
    public HealthResponse health() {
        return new HealthResponse("UP", "Village Homepage is running");
    }

    public record HealthResponse(String status, String message) {
    }
}
