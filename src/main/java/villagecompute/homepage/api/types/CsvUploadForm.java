/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.api.types;

import org.jboss.resteasy.reactive.PartType;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Multipart form for CSV file uploads to bulk import endpoint.
 *
 * <p>
 * Accepts CSV files with columns: url (required), title (optional), description (optional), suggested_categories
 * (optional). Maximum file size controlled by quarkus.http.limits.max-body-size.
 *
 * <p>
 * <b>Feature F13.14:</b> Bulk import workflow with AI categorization assistance.
 */
public class CsvUploadForm {

    /**
     * Uploaded CSV file.
     */
    @FormParam("file")
    @PartType(MediaType.APPLICATION_OCTET_STREAM)
    public FileUpload file;

    /**
     * Optional description for this upload batch.
     */
    @FormParam("description")
    public String description;
}
