package villagecompute.homepage.services;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.exceptions.ValidationException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for SlotCapacityValidator.
 *
 * <p>
 * Coverage:
 * <ul>
 * <li>Template-specific slot validation (public_homepage, your_times, your_report)</li>
 * <li>Slot capacity enforcement</li>
 * <li>Invalid slot name detection</li>
 * <li>Position validation</li>
 * <li>Available slot queries</li>
 * </ul>
 */
@QuarkusTest
public class SlotCapacityValidatorTest {

    @Inject
    SlotCapacityValidator validator;

    @Test
    public void testPublicHomepageSlot_Valid() {
        UUID profileId = UUID.randomUUID();
        Map<String, Object> slotAssignment = Map.of("slot", "grid", "position", 0);

        // Should not throw
        assertDoesNotThrow(() -> validator.validateSlotAssignment(profileId, "public_homepage", slotAssignment));
    }

    @Test
    public void testYourTimesSlot_ValidHeadline() {
        UUID profileId = UUID.randomUUID();
        Map<String, Object> slotAssignment = Map.of("slot", "headline", "position", 0);

        assertDoesNotThrow(() -> validator.validateSlotAssignment(profileId, "your_times", slotAssignment));
    }

    @Test
    public void testYourTimesSlot_ValidSecondary() {
        UUID profileId = UUID.randomUUID();
        Map<String, Object> slotAssignment = Map.of("slot", "secondary", "position", 0);

        assertDoesNotThrow(() -> validator.validateSlotAssignment(profileId, "your_times", slotAssignment));
    }

    @Test
    public void testYourTimesSlot_ValidSidebar() {
        UUID profileId = UUID.randomUUID();
        Map<String, Object> slotAssignment = Map.of("slot", "sidebar", "position", 0);

        assertDoesNotThrow(() -> validator.validateSlotAssignment(profileId, "your_times", slotAssignment));
    }

    @Test
    public void testYourTimesSlot_InvalidSlotName() {
        UUID profileId = UUID.randomUUID();
        Map<String, Object> slotAssignment = Map.of("slot", "invalid_slot", "position", 0);

        ValidationException exception = assertThrows(ValidationException.class,
                () -> validator.validateSlotAssignment(profileId, "your_times", slotAssignment));

        assertTrue(exception.getMessage().contains("Invalid slot for your_times template"));
        assertTrue(exception.getMessage().contains("invalid_slot"));
    }

    @Test
    public void testYourTimesSlot_PositionOutOfRange() {
        UUID profileId = UUID.randomUUID();
        Map<String, Object> slotAssignment = Map.of("slot", "headline", "position", 5);

        ValidationException exception = assertThrows(ValidationException.class,
                () -> validator.validateSlotAssignment(profileId, "your_times", slotAssignment));

        assertTrue(exception.getMessage().contains("Position 5 exceeds slot capacity"));
    }

    @Test
    public void testYourReportSlot_ValidTopStories() {
        UUID profileId = UUID.randomUUID();
        Map<String, Object> slotAssignment = Map.of("slot", "top_stories", "position", 0);

        assertDoesNotThrow(() -> validator.validateSlotAssignment(profileId, "your_report", slotAssignment));
    }

    @Test
    public void testYourReportSlot_ValidBusiness() {
        UUID profileId = UUID.randomUUID();
        Map<String, Object> slotAssignment = Map.of("slot", "business", "position", 0);

        assertDoesNotThrow(() -> validator.validateSlotAssignment(profileId, "your_report", slotAssignment));
    }

    @Test
    public void testYourReportSlot_InvalidSection() {
        UUID profileId = UUID.randomUUID();
        Map<String, Object> slotAssignment = Map.of("slot", "invalid_section", "position", 0);

        ValidationException exception = assertThrows(ValidationException.class,
                () -> validator.validateSlotAssignment(profileId, "your_report", slotAssignment));

        assertTrue(exception.getMessage().contains("Invalid section for your_report template"));
        assertTrue(exception.getMessage().contains("invalid_section"));
    }

    @Test
    public void testEmptySlotAssignment() {
        UUID profileId = UUID.randomUUID();
        Map<String, Object> slotAssignment = Map.of();

        ValidationException exception = assertThrows(ValidationException.class,
                () -> validator.validateSlotAssignment(profileId, "your_times", slotAssignment));

        assertTrue(exception.getMessage().contains("Slot assignment cannot be empty"));
    }

    @Test
    public void testNullSlotAssignment() {
        UUID profileId = UUID.randomUUID();

        ValidationException exception = assertThrows(ValidationException.class,
                () -> validator.validateSlotAssignment(profileId, "your_times", null));

        assertTrue(exception.getMessage().contains("Slot assignment cannot be empty"));
    }

    @Test
    public void testNegativePosition() {
        UUID profileId = UUID.randomUUID();
        Map<String, Object> slotAssignment = Map.of("slot", "headline", "position", -1);

        ValidationException exception = assertThrows(ValidationException.class,
                () -> validator.validateSlotAssignment(profileId, "your_times", slotAssignment));

        assertTrue(exception.getMessage().contains("Slot position must be >= 0"));
    }

    @Test
    public void testUnknownTemplate() {
        UUID profileId = UUID.randomUUID();
        Map<String, Object> slotAssignment = Map.of("slot", "grid", "position", 0);

        ValidationException exception = assertThrows(ValidationException.class,
                () -> validator.validateSlotAssignment(profileId, "unknown_template", slotAssignment));

        assertTrue(exception.getMessage().contains("Unknown template"));
    }

    @Test
    public void testGetSlotCapacity_YourTimes() {
        assertEquals(1, validator.getSlotCapacity("your_times", "headline"));
        assertEquals(3, validator.getSlotCapacity("your_times", "secondary"));
        assertEquals(2, validator.getSlotCapacity("your_times", "sidebar"));
        assertEquals(0, validator.getSlotCapacity("your_times", "invalid"));
    }

    @Test
    public void testGetSlotCapacity_PublicHomepage() {
        assertEquals(-1, validator.getSlotCapacity("public_homepage", "grid"));
    }

    @Test
    public void testGetSlotCapacity_YourReport() {
        assertEquals(-1, validator.getSlotCapacity("your_report", "top_stories"));
    }

    @Test
    public void testGetAvailableSlots_YourTimes() {
        List<String> slots = validator.getAvailableSlots("your_times");
        assertEquals(3, slots.size());
        assertTrue(slots.contains("headline"));
        assertTrue(slots.contains("secondary"));
        assertTrue(slots.contains("sidebar"));
    }

    @Test
    public void testGetAvailableSlots_PublicHomepage() {
        List<String> slots = validator.getAvailableSlots("public_homepage");
        assertEquals(1, slots.size());
        assertTrue(slots.contains("grid"));
    }

    @Test
    public void testGetAvailableSlots_YourReport() {
        List<String> slots = validator.getAvailableSlots("your_report");
        assertEquals(6, slots.size());
        assertTrue(slots.contains("top_stories"));
        assertTrue(slots.contains("business"));
        assertTrue(slots.contains("technology"));
        assertTrue(slots.contains("sports"));
        assertTrue(slots.contains("entertainment"));
        assertTrue(slots.contains("opinion"));
    }

    @Test
    public void testGetAvailableSlots_UnknownTemplate() {
        List<String> slots = validator.getAvailableSlots("unknown_template");
        assertTrue(slots.isEmpty());
    }
}
