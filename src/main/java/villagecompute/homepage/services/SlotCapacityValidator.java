package villagecompute.homepage.services;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.ProfileCuratedArticle;
import villagecompute.homepage.exceptions.ValidationException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Validator for template-specific slot capacity rules.
 *
 * <p>
 * Each profile template has different slot constraints. This service validates slot assignments against
 * template-specific capacity limits before persisting curated articles.
 *
 * <p>
 * <b>Template Slot Definitions:</b>
 * <ul>
 * <li><b>public_homepage:</b> Flexible grid (no hard limits)</li>
 * <li><b>your_times:</b> Fixed slots: headline (1), secondary (3), sidebar (2)</li>
 * <li><b>your_report:</b> Section-based: each section allows multiple articles</li>
 * </ul>
 *
 * <p>
 * <b>Validation Rules:</b>
 * <ol>
 * <li>Slot name must be valid for the template</li>
 * <li>Slot capacity must not be exceeded</li>
 * <li>Position must be within valid range</li>
 * </ol>
 *
 * <p>
 * <b>Feature:</b> F11.4-F11.7 - Profile Curated Articles
 * </p>
 */
@ApplicationScoped
public class SlotCapacityValidator {

    private static final Logger LOG = Logger.getLogger(SlotCapacityValidator.class);

    // Template: your_times slot definitions
    private static final Map<String, Integer> YOUR_TIMES_SLOTS = Map.of("headline", 1, "secondary", 3, "sidebar", 2);

    // Template: your_report section names (unlimited articles per section)
    private static final Set<String> YOUR_REPORT_SECTIONS = Set.of("top_stories", "business", "technology", "sports",
            "entertainment", "opinion");

    /**
     * Validates slot assignment for a profile template.
     *
     * @param profileId
     *            profile UUID
     * @param template
     *            template type (public_homepage, your_times, your_report)
     * @param slotAssignment
     *            slot assignment map (must contain "slot" key)
     * @throws ValidationException
     *             if slot assignment is invalid or capacity exceeded
     */
    public void validateSlotAssignment(UUID profileId, String template, Map<String, Object> slotAssignment) {
        if (slotAssignment == null || slotAssignment.isEmpty()) {
            throw new ValidationException("Slot assignment cannot be empty");
        }

        String slotName = (String) slotAssignment.get("slot");
        if (slotName == null || slotName.isBlank()) {
            throw new ValidationException("Slot name is required");
        }

        Integer position = (Integer) slotAssignment.get("position");
        if (position == null || position < 0) {
            throw new ValidationException("Slot position must be >= 0");
        }

        switch (template) {
            case "public_homepage" :
                validatePublicHomepage(slotName);
                break;

            case "your_times" :
                validateYourTimes(profileId, slotName, position);
                break;

            case "your_report" :
                validateYourReport(slotName);
                break;

            default :
                throw new ValidationException("Unknown template: " + template);
        }
    }

    /**
     * Validates slot for public_homepage template (flexible grid, no hard limits).
     *
     * @param slotName
     *            slot name
     */
    private void validatePublicHomepage(String slotName) {
        // public_homepage has flexible grid with no hard slot limits
        // Only validate that slot name is "grid" (the standard slot type)
        if (!"grid".equals(slotName)) {
            LOG.warnf("Unusual slot name for public_homepage template: %s (expected 'grid')", slotName);
        }
    }

    /**
     * Validates slot for your_times template (fixed slots with capacity limits).
     *
     * @param profileId
     *            profile UUID
     * @param slotName
     *            slot name (headline, secondary, sidebar)
     * @param position
     *            position within slot
     * @throws ValidationException
     *             if slot invalid or capacity exceeded
     */
    private void validateYourTimes(UUID profileId, String slotName, Integer position) {
        // Check if slot name is valid for your_times
        if (!YOUR_TIMES_SLOTS.containsKey(slotName)) {
            throw new ValidationException("Invalid slot for your_times template: " + slotName + " (valid slots: "
                    + YOUR_TIMES_SLOTS.keySet() + ")");
        }

        // Check capacity for this slot
        int capacity = YOUR_TIMES_SLOTS.get(slotName);
        long currentCount = countArticlesInSlot(profileId, slotName);

        if (currentCount >= capacity) {
            throw new ValidationException(
                    String.format("Slot '%s' is full (capacity: %d, current: %d)", slotName, capacity, currentCount));
        }

        // Check position is within capacity
        if (position >= capacity) {
            throw new ValidationException(
                    String.format("Position %d exceeds slot capacity (max: %d)", position, capacity - 1));
        }
    }

    /**
     * Validates slot for your_report template (section-based, unlimited per section).
     *
     * @param slotName
     *            slot name (section name)
     * @throws ValidationException
     *             if section invalid
     */
    private void validateYourReport(String slotName) {
        // Check if section name is valid
        if (!YOUR_REPORT_SECTIONS.contains(slotName)) {
            throw new ValidationException("Invalid section for your_report template: " + slotName + " (valid sections: "
                    + YOUR_REPORT_SECTIONS + ")");
        }
    }

    /**
     * Counts active articles already assigned to a slot.
     *
     * @param profileId
     *            profile UUID
     * @param slotName
     *            slot name
     * @return count of articles in this slot
     */
    private long countArticlesInSlot(UUID profileId, String slotName) {
        // Fetch all active articles and check slot assignment in Java
        // (JSONB queries in HQL are not straightforward without native SQL)
        List<ProfileCuratedArticle> articles = ProfileCuratedArticle.findActive(profileId);

        return articles.stream().filter(article -> slotName.equals(article.slotAssignment.get("slot"))).count();
    }

    /**
     * Gets capacity for a specific slot in a template.
     *
     * @param template
     *            template type
     * @param slotName
     *            slot name
     * @return capacity (-1 for unlimited)
     */
    public int getSlotCapacity(String template, String slotName) {
        switch (template) {
            case "public_homepage" :
                return -1; // Unlimited

            case "your_times" :
                return YOUR_TIMES_SLOTS.getOrDefault(slotName, 0);

            case "your_report" :
                return -1; // Unlimited per section

            default :
                return 0;
        }
    }

    /**
     * Gets available slot names for a template.
     *
     * @param template
     *            template type
     * @return list of valid slot names
     */
    public List<String> getAvailableSlots(String template) {
        switch (template) {
            case "public_homepage" :
                return List.of("grid");

            case "your_times" :
                return List.copyOf(YOUR_TIMES_SLOTS.keySet());

            case "your_report" :
                return List.copyOf(YOUR_REPORT_SECTIONS);

            default :
                return List.of();
        }
    }
}
