package villagecompute.homepage.data.models;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReservedUsername model (Feature F11).
 *
 * <p>
 * Coverage:
 * <ul>
 * <li>isReserved() validation method</li>
 * <li>reserve() and unreserve() methods</li>
 * <li>seedDefaults() bootstrap</li>
 * <li>findByUsername() and findByReason() query methods</li>
 * <li>Duplicate prevention</li>
 * </ul>
 */
@QuarkusTest
public class ReservedUsernameTest {

    @BeforeEach
    @Transactional
    public void setUp() {
        // Clean up any test reserved names
        ReservedUsername.delete("username LIKE 'testreserved%'");
    }

    @AfterEach
    @Transactional
    public void tearDown() {
        // Clean up test reserved names
        ReservedUsername.delete("username LIKE 'testreserved%'");
    }

    // ========== isReserved() Tests ==========

    @Test
    @Transactional
    public void testIsReserved_True() {
        ReservedUsername.reserve("testreserved123", "Test: Reserved for testing");
        assertTrue(ReservedUsername.isReserved("testreserved123"));
    }

    @Test
    @Transactional
    public void testIsReserved_False() {
        assertFalse(ReservedUsername.isReserved("notreserved123"));
    }

    @Test
    @Transactional
    public void testIsReserved_CaseInsensitive() {
        ReservedUsername.reserve("testreserved123", "Test: Reserved for testing");
        assertTrue(ReservedUsername.isReserved("TESTRESERVED123"));
        assertTrue(ReservedUsername.isReserved("TestReserved123"));
    }

    @Test
    public void testIsReserved_NullOrBlank() {
        assertFalse(ReservedUsername.isReserved(null));
        assertFalse(ReservedUsername.isReserved(""));
        assertFalse(ReservedUsername.isReserved("   "));
    }

    // ========== reserve() Tests ==========

    @Test
    @Transactional
    public void testReserve_Success() {
        ReservedUsername reserved = ReservedUsername.reserve("testreserved123", "Test: Reserved for testing");

        assertNotNull(reserved.id);
        assertEquals("testreserved123", reserved.username);
        assertEquals("Test: Reserved for testing", reserved.reason);
        assertNotNull(reserved.reservedAt);
    }

    @Test
    @Transactional
    public void testReserve_Duplicate() {
        ReservedUsername.reserve("testreserved123", "Test: Reserved for testing");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            ReservedUsername.reserve("testreserved123", "Test: Duplicate");
        });
        assertTrue(ex.getMessage().contains("already reserved"));
    }

    @Test
    @Transactional
    public void testReserve_NormalizedToLowercase() {
        ReservedUsername reserved = ReservedUsername.reserve("TESTRESERVED123", "Test: Reserved for testing");
        assertEquals("testreserved123", reserved.username);
    }

    @Test
    @Transactional
    public void testReserve_BlankUsername() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            ReservedUsername.reserve("", "Test: Reserved for testing");
        });
        assertTrue(ex.getMessage().contains("Username cannot be blank"));
    }

    @Test
    @Transactional
    public void testReserve_BlankReason() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            ReservedUsername.reserve("testreserved123", "");
        });
        assertTrue(ex.getMessage().contains("Reason cannot be blank"));
    }

    // ========== unreserve() Tests ==========

    @Test
    @Transactional
    public void testUnreserve_Success() {
        ReservedUsername.reserve("testreserved123", "Test: Reserved for testing");
        assertTrue(ReservedUsername.isReserved("testreserved123"));

        boolean result = ReservedUsername.unreserve("testreserved123");
        assertTrue(result);
        assertFalse(ReservedUsername.isReserved("testreserved123"));
    }

    @Test
    @Transactional
    public void testUnreserve_NotFound() {
        boolean result = ReservedUsername.unreserve("notreserved123");
        assertFalse(result);
    }

    @Test
    @Transactional
    public void testUnreserve_CaseInsensitive() {
        ReservedUsername.reserve("testreserved123", "Test: Reserved for testing");

        boolean result = ReservedUsername.unreserve("TESTRESERVED123");
        assertTrue(result);
        assertFalse(ReservedUsername.isReserved("testreserved123"));
    }

    // ========== findByUsername() Tests ==========

    @Test
    @Transactional
    public void testFindByUsername_Found() {
        ReservedUsername.reserve("testreserved123", "Test: Reserved for testing");

        Optional<ReservedUsername> found = ReservedUsername.findByUsername("testreserved123");
        assertTrue(found.isPresent());
        assertEquals("testreserved123", found.get().username);
    }

    @Test
    @Transactional
    public void testFindByUsername_NotFound() {
        Optional<ReservedUsername> found = ReservedUsername.findByUsername("notreserved123");
        assertFalse(found.isPresent());
    }

    @Test
    @Transactional
    public void testFindByUsername_CaseInsensitive() {
        ReservedUsername.reserve("testreserved123", "Test: Reserved for testing");

        Optional<ReservedUsername> found = ReservedUsername.findByUsername("TESTRESERVED123");
        assertTrue(found.isPresent());
        assertEquals("testreserved123", found.get().username);
    }

    // ========== findByReason() Tests ==========

    @Test
    @Transactional
    public void testFindByReason() {
        ReservedUsername.reserve("testreserved1", "Test: Category A");
        ReservedUsername.reserve("testreserved2", "Test: Category A");
        ReservedUsername.reserve("testreserved3", "Test: Category B");

        List<ReservedUsername> found = ReservedUsername.findByReason("Test: Category A%");
        assertTrue(found.size() >= 2);
        assertTrue(found.stream().anyMatch(r -> r.username.equals("testreserved1")));
        assertTrue(found.stream().anyMatch(r -> r.username.equals("testreserved2")));
    }

    // ========== listAll() Tests ==========

    @Test
    @Transactional
    public void testListAll() {
        long initialCount = ReservedUsername.countAll();

        ReservedUsername.reserve("testreserved1", "Test: Reserved 1");
        ReservedUsername.reserve("testreserved2", "Test: Reserved 2");

        List<ReservedUsername> all = ReservedUsername.listAll();
        assertTrue(all.size() >= initialCount + 2);
    }

    // ========== seedDefaults() Tests ==========

    @Test
    @Transactional
    public void testSeedDefaults() {
        // Clear all reserved names first
        long initialCount = ReservedUsername.countAll();

        // Seed defaults
        ReservedUsername.seedDefaults();

        // Verify system names
        assertTrue(ReservedUsername.isReserved("admin"));
        assertTrue(ReservedUsername.isReserved("api"));
        assertTrue(ReservedUsername.isReserved("cdn"));
        assertTrue(ReservedUsername.isReserved("www"));

        // Verify feature names
        assertTrue(ReservedUsername.isReserved("good-sites"));
        assertTrue(ReservedUsername.isReserved("marketplace"));
        assertTrue(ReservedUsername.isReserved("calendar"));
        assertTrue(ReservedUsername.isReserved("directory"));

        // Verify admin role names
        assertTrue(ReservedUsername.isReserved("support"));
        assertTrue(ReservedUsername.isReserved("ops"));
        assertTrue(ReservedUsername.isReserved("moderator"));

        // Verify common names
        assertTrue(ReservedUsername.isReserved("help"));
        assertTrue(ReservedUsername.isReserved("about"));
        assertTrue(ReservedUsername.isReserved("contact"));
        assertTrue(ReservedUsername.isReserved("terms"));
        assertTrue(ReservedUsername.isReserved("privacy"));
    }

    @Test
    @Transactional
    public void testSeedDefaults_Idempotent() {
        long countBefore = ReservedUsername.countAll();

        // Seed defaults twice
        ReservedUsername.seedDefaults();
        long countAfterFirst = ReservedUsername.countAll();

        ReservedUsername.seedDefaults();
        long countAfterSecond = ReservedUsername.countAll();

        // Should not create duplicates
        assertEquals(countAfterFirst, countAfterSecond);
    }

    // ========== countAll() Tests ==========

    @Test
    @Transactional
    public void testCountAll() {
        long initialCount = ReservedUsername.countAll();

        ReservedUsername.reserve("testreserved1", "Test: Reserved 1");
        assertEquals(initialCount + 1, ReservedUsername.countAll());

        ReservedUsername.reserve("testreserved2", "Test: Reserved 2");
        assertEquals(initialCount + 2, ReservedUsername.countAll());

        ReservedUsername.unreserve("testreserved1");
        assertEquals(initialCount + 1, ReservedUsername.countAll());
    }
}
