/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.data.models;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Parameters;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;

import org.jboss.logging.Logger;

import villagecompute.homepage.api.types.AiCategorySuggestionType;
import villagecompute.homepage.api.types.DirectoryAiSuggestionType;

/**
 * AI categorization suggestion for bulk-imported Good Sites directory entries.
 *
 * <p>
 * This entity stores AI-generated category recommendations pending admin review. Bulk CSV imports create suggestion
 * records which are processed by AiCategorizationService, then reviewed by admins via DirectoryImportResource endpoints
 * per Feature F13.14.
 *
 * <p>
 * <b>Workflow:</b>
 * <ol>
 * <li>Admin uploads CSV → BulkImportJobHandler creates DirectoryAiSuggestion records with status=pending</li>
 * <li>Job handler calls AiCategorizationService → Updates with AI suggestions, confidence, reasoning</li>
 * <li>Admin reviews via /admin/api/directory/import/suggestions → Can approve or override AI selection</li>
 * <li>On approval → DirectorySite + DirectorySiteCategory records created, status=approved</li>
 * <li>On rejection → status=rejected, no site created</li>
 * </ol>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P2/P10 (AI Budget): Token usage tracked to ai_usage_tracking table, shares $500/month limit</li>
 * <li>F13.14 (Bulk Import): Admin diff view for AI suggestion vs manual override (training data)</li>
 * </ul>
 *
 * @see villagecompute.homepage.services.AiCategorizationService
 * @see villagecompute.homepage.jobs.BulkImportJobHandler
 * @see villagecompute.homepage.api.rest.admin.DirectoryImportResource
 */
@Entity
@Table(
        name = "directory_ai_suggestions")
@NamedQuery(
        name = DirectoryAiSuggestion.QUERY_FIND_PENDING,
        query = "FROM DirectoryAiSuggestion WHERE status = 'pending' ORDER BY createdAt")
@NamedQuery(
        name = DirectoryAiSuggestion.QUERY_FIND_BY_STATUS,
        query = "FROM DirectoryAiSuggestion WHERE status = :status ORDER BY createdAt DESC")
@NamedQuery(
        name = DirectoryAiSuggestion.QUERY_FIND_BY_UPLOADED_BY,
        query = "FROM DirectoryAiSuggestion WHERE uploadedByUserId = :userId ORDER BY createdAt DESC")
public class DirectoryAiSuggestion extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(DirectoryAiSuggestion.class);

    public static final String QUERY_FIND_PENDING = "DirectoryAiSuggestion.findPending";
    public static final String QUERY_FIND_BY_STATUS = "DirectoryAiSuggestion.findByStatus";
    public static final String QUERY_FIND_BY_UPLOADED_BY = "DirectoryAiSuggestion.findByUploadedBy";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            nullable = false)
    public String url;

    @Column(
            nullable = false)
    public String domain;

    @Column(
            nullable = false)
    public String title;

    @Column
    public String description;

    @Column(
            name = "og_image_url")
    public String ogImageUrl;

    @Column(
            name = "suggested_category_ids",
            columnDefinition = "uuid[]")
    public UUID[] suggestedCategoryIds;

    @Column
    public String reasoning;

    @Column(
            precision = 3,
            scale = 2)
    public BigDecimal confidence;

    @Column(
            name = "admin_selected_category_ids",
            columnDefinition = "uuid[]")
    public UUID[] adminSelectedCategoryIds;

    @Column(
            nullable = false)
    public String status = STATUS_PENDING;

    @Column(
            name = "uploaded_by_user_id",
            nullable = false)
    public UUID uploadedByUserId;

    @Column(
            name = "reviewed_by_user_id")
    public UUID reviewedByUserId;

    @Column(
            name = "tokens_input")
    public Long tokensInput;

    @Column(
            name = "tokens_output")
    public Long tokensOutput;

    @Column(
            name = "estimated_cost_cents")
    public Integer estimatedCostCents;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt;

    /**
     * Finds all pending suggestions (awaiting AI categorization or admin review).
     *
     * @return List of pending suggestions ordered by creation time
     */
    public static List<DirectoryAiSuggestion> findPending() {
        return find("#" + QUERY_FIND_PENDING).list();
    }

    /**
     * Finds pending suggestions that have not yet been processed by AI (suggestedCategoryIds is null).
     *
     * @return List of unprocessed suggestions
     */
    public static List<DirectoryAiSuggestion> findUnprocessed() {
        return find("status = ?1 AND suggestedCategoryIds IS NULL ORDER BY createdAt", STATUS_PENDING).list();
    }

    /**
     * Finds suggestions by status.
     *
     * @param status
     *            pending, approved, or rejected
     * @return List of suggestions with matching status
     */
    public static List<DirectoryAiSuggestion> findByStatus(String status) {
        return find("#" + QUERY_FIND_BY_STATUS, Parameters.with("status", status)).list();
    }

    /**
     * Finds suggestions uploaded by a specific user.
     *
     * @param userId
     *            the uploader user ID
     * @return List of suggestions uploaded by this user
     */
    public static List<DirectoryAiSuggestion> findByUploadedBy(UUID userId) {
        return find("#" + QUERY_FIND_BY_UPLOADED_BY, Parameters.with("userId", userId)).list();
    }

    /**
     * Finds suggestion by URL (for duplicate detection).
     *
     * @param url
     *            the normalized URL
     * @return Optional containing suggestion if found
     */
    public static Optional<DirectoryAiSuggestion> findByUrl(String url) {
        return find("url = ?1", url).firstResultOptional();
    }

    /**
     * Creates a new AI suggestion record.
     *
     * @param suggestion
     *            the suggestion to persist
     * @return the persisted suggestion with generated ID
     */
    public static DirectoryAiSuggestion create(DirectoryAiSuggestion suggestion) {
        QuarkusTransaction.requiringNew().run(() -> {
            suggestion.createdAt = Instant.now();
            suggestion.updatedAt = Instant.now();
            suggestion.persist();
            LOG.infof("Created AI suggestion: id=%s, url=%s, uploadedBy=%s", suggestion.id, suggestion.url,
                    suggestion.uploadedByUserId);
        });
        return suggestion;
    }

    /**
     * Updates an AI suggestion with categorization results.
     *
     * @param suggestionId
     *            the suggestion ID to update
     * @param aiSuggestion
     *            the AI-generated categorization
     * @param tokensInput
     *            input token count
     * @param tokensOutput
     *            output token count
     * @param costCents
     *            estimated cost in cents
     */
    public static void updateSuggestion(UUID suggestionId, AiCategorySuggestionType aiSuggestion, long tokensInput,
            long tokensOutput, int costCents) {
        QuarkusTransaction.requiringNew().run(() -> {
            DirectoryAiSuggestion managed = findById(suggestionId);
            if (managed == null) {
                throw new IllegalStateException("AI suggestion not found: " + suggestionId);
            }

            // Extract category IDs from AI suggestion (validate against database)
            List<UUID> categoryIds = new ArrayList<>();
            for (AiCategorySuggestionType.SuggestedCategory cat : aiSuggestion.suggestedCategories()) {
                Optional<DirectoryCategory> category = DirectoryCategory.findBySlug(cat.categorySlug());
                if (category.isEmpty()) {
                    LOG.warnf("AI suggested invalid category slug: %s for URL: %s", cat.categorySlug(), managed.url);
                    continue; // Skip invalid categories
                }
                categoryIds.add(category.get().id);
            }

            if (categoryIds.isEmpty()) {
                LOG.warnf("No valid categories suggested by AI for URL: %s, keeping as pending for manual review",
                        managed.url);
                return; // Don't update, leave for manual categorization
            }

            // Update fields
            managed.suggestedCategoryIds = categoryIds.toArray(new UUID[0]);
            managed.reasoning = aiSuggestion.overallReasoning();
            managed.confidence = BigDecimal.valueOf(aiSuggestion.confidence());
            managed.tokensInput = tokensInput;
            managed.tokensOutput = tokensOutput;
            managed.estimatedCostCents = costCents;
            managed.updatedAt = Instant.now();

            managed.persist();

            LOG.infof("Updated AI suggestion: id=%s, url=%s, categories=%d, confidence=%.2f, costCents=%d", managed.id,
                    managed.url, categoryIds.size(), aiSuggestion.confidence(), costCents);
        });
    }

    /**
     * Approves a suggestion and marks it for site creation.
     *
     * @param suggestionId
     *            the suggestion ID to approve
     * @param adminUserId
     *            the admin who approved
     * @param overrideCategoryIds
     *            optional admin-selected categories (null to use AI suggestion)
     */
    public static void approve(UUID suggestionId, UUID adminUserId, List<UUID> overrideCategoryIds) {
        QuarkusTransaction.requiringNew().run(() -> {
            DirectoryAiSuggestion managed = findById(suggestionId);
            if (managed == null) {
                throw new IllegalStateException("AI suggestion not found: " + suggestionId);
            }

            if (overrideCategoryIds != null && !overrideCategoryIds.isEmpty()) {
                managed.adminSelectedCategoryIds = overrideCategoryIds.toArray(new UUID[0]);
                LOG.infof("Admin override: id=%s, AI suggested %d categories, admin selected %d", managed.id,
                        managed.suggestedCategoryIds != null ? managed.suggestedCategoryIds.length : 0,
                        overrideCategoryIds.size());
            }

            managed.status = STATUS_APPROVED;
            managed.reviewedByUserId = adminUserId;
            managed.updatedAt = Instant.now();
            managed.persist();

            LOG.infof("Approved AI suggestion: id=%s, url=%s, reviewedBy=%s", managed.id, managed.url, adminUserId);
        });
    }

    /**
     * Rejects a suggestion (site will not be created).
     *
     * @param suggestionId
     *            the suggestion ID to reject
     * @param adminUserId
     *            the admin who rejected
     */
    public static void reject(UUID suggestionId, UUID adminUserId) {
        QuarkusTransaction.requiringNew().run(() -> {
            DirectoryAiSuggestion managed = findById(suggestionId);
            if (managed == null) {
                throw new IllegalStateException("AI suggestion not found: " + suggestionId);
            }

            managed.status = STATUS_REJECTED;
            managed.reviewedByUserId = adminUserId;
            managed.updatedAt = Instant.now();
            managed.persist();

            LOG.infof("Rejected AI suggestion: id=%s, url=%s, reviewedBy=%s", managed.id, managed.url, adminUserId);
        });
    }

    /**
     * Converts this entity to API type for admin display.
     *
     * @return DirectoryAiSuggestionType DTO
     */
    public DirectoryAiSuggestionType toApiType() {
        List<DirectoryAiSuggestionType.CategorySelectionType> aiCategories = new ArrayList<>();
        if (suggestedCategoryIds != null) {
            for (UUID catId : suggestedCategoryIds) {
                DirectoryCategory cat = DirectoryCategory.findById(catId);
                if (cat != null) {
                    aiCategories.add(new DirectoryAiSuggestionType.CategorySelectionType(cat.id, cat.slug,
                            buildCategoryPath(cat), reasoning != null ? reasoning : ""));
                }
            }
        }

        List<DirectoryAiSuggestionType.CategorySelectionType> adminCategories = null;
        if (adminSelectedCategoryIds != null) {
            adminCategories = new ArrayList<>();
            for (UUID catId : adminSelectedCategoryIds) {
                DirectoryCategory cat = DirectoryCategory.findById(catId);
                if (cat != null) {
                    adminCategories.add(new DirectoryAiSuggestionType.CategorySelectionType(cat.id, cat.slug,
                            buildCategoryPath(cat), "Admin override"));
                }
            }
        }

        return new DirectoryAiSuggestionType(id, url, domain, title, description, ogImageUrl, aiCategories,
                adminCategories, confidence != null ? confidence.doubleValue() : null, reasoning, status,
                uploadedByUserId, reviewedByUserId, tokensInput, tokensOutput, estimatedCostCents, createdAt,
                updatedAt);
    }

    /**
     * Builds category path string (e.g., "Computers > Programming > Java").
     */
    private String buildCategoryPath(DirectoryCategory category) {
        List<String> path = new ArrayList<>();
        DirectoryCategory current = category;

        while (current != null) {
            path.add(0, current.name); // Prepend to build path from root to leaf
            if (current.parentId != null) {
                current = DirectoryCategory.findById(current.parentId);
            } else {
                current = null;
            }
        }

        return String.join(" > ", path);
    }

    /**
     * Approves this suggestion (instance method for use by REST endpoint).
     *
     * @param adminUserId
     *            the admin who approved
     * @param overrideCategoryIds
     *            optional admin-selected categories (null to use AI suggestion)
     */
    public void approve(UUID adminUserId, UUID[] overrideCategoryIds) {
        if (overrideCategoryIds != null && overrideCategoryIds.length > 0) {
            this.adminSelectedCategoryIds = overrideCategoryIds;
            LOG.infof("Admin override: id=%s, AI suggested %d categories, admin selected %d", this.id,
                    this.suggestedCategoryIds != null ? this.suggestedCategoryIds.length : 0,
                    overrideCategoryIds.length);
        }

        this.status = STATUS_APPROVED;
        this.reviewedByUserId = adminUserId;
        this.updatedAt = Instant.now();
        this.persist();

        LOG.infof("Approved AI suggestion: id=%s, url=%s, reviewedBy=%s", this.id, this.url, adminUserId);
    }

    /**
     * Rejects this suggestion (instance method for use by REST endpoint).
     *
     * @param adminUserId
     *            the admin who rejected
     */
    public void reject(UUID adminUserId) {
        this.status = STATUS_REJECTED;
        this.reviewedByUserId = adminUserId;
        this.updatedAt = Instant.now();
        this.persist();

        LOG.infof("Rejected AI suggestion: id=%s, url=%s, reviewedBy=%s", this.id, this.url, adminUserId);
    }

    /**
     * Returns final category IDs to use (admin override if present, otherwise AI suggestion).
     *
     * @return array of category IDs for site creation
     */
    public UUID[] getFinalCategoryIds() {
        if (adminSelectedCategoryIds != null && adminSelectedCategoryIds.length > 0) {
            return adminSelectedCategoryIds;
        }
        return suggestedCategoryIds != null ? suggestedCategoryIds : new UUID[0];
    }

    /**
     * Checks if admin has overridden AI suggestion.
     *
     * @return true if admin selected different categories
     */
    public boolean hasAdminOverride() {
        return adminSelectedCategoryIds != null && adminSelectedCategoryIds.length > 0;
    }
}
