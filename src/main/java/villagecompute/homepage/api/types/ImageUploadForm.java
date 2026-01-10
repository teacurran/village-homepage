package villagecompute.homepage.api.types;

import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.PartType;

import java.io.InputStream;

/**
 * Multipart form data for listing image uploads.
 *
 * Accepts image file with optional display order specification.
 * Used by POST /api/marketplace/listings/{listingId}/images endpoint.
 */
public class ImageUploadForm {

    @FormParam("file")
    @PartType(MediaType.APPLICATION_OCTET_STREAM)
    public InputStream file;

    @FormParam("fileName")
    @PartType(MediaType.TEXT_PLAIN)
    public String fileName;

    @FormParam("contentType")
    @PartType(MediaType.TEXT_PLAIN)
    public String contentType;

    @FormParam("displayOrder")
    @PartType(MediaType.TEXT_PLAIN)
    public Integer displayOrder;
}
