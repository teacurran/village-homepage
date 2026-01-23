package villagecompute.homepage;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract base class for all integration tests requiring PostgreSQL 17 + PostGIS database.
 *
 * <p>
 * This class provides:
 * <ul>
 * <li>Testcontainers PostgreSQL 17 with PostGIS extension (via application-test.properties)</li>
 * <li>@TestTransaction annotation ensures database rollback after each test</li>
 * <li>Helper methods for common entity assertions (assertEntityExists, assertEntityDeleted)</li>
 * <li>Lifecycle hooks for setup/teardown if needed by subclasses</li>
 * </ul>
 *
 * <p>
 * <b>Usage Example:</b>
 *
 * <pre>
 * &#64;QuarkusTest
 * public class UserServiceTest extends BaseIntegrationTest {
 *     &#64;Test
 *     &#64;TestTransaction
 *     public void testCreateUser() {
 *         User user = User.createAnonymous();
 *         assertEntityExists(User.class, user.id);
 *     }
 * }
 * </pre>
 *
 * <p>
 * <b>Database Configuration:</b> Tests use PostgreSQL 17 Alpine image via Testcontainers JDBC driver. PostGIS extension
 * is enabled via initialization script at db/init-test-postgis.sql. See application-test.properties for configuration
 * details.
 *
 * <p>
 * <b>Test Isolation:</b> Use &#64;TestTransaction annotation on test methods to ensure automatic rollback. This
 * prevents test pollution and eliminates need for manual cleanup code.
 *
 * <p>
 * <b>Ref:</b> Foundation Blueprint Section 3.5 (Testing Strategy), Task I1.T2
 *
 * @see io.quarkus.test.junit.QuarkusTest
 * @see jakarta.transaction.Transactional
 */
public abstract class BaseIntegrationTest {

    /**
     * Hook executed before each test method. Override in subclasses if custom setup is needed. Default implementation
     * does nothing.
     */
    @BeforeEach
    protected void setUp() {
        // Subclasses can override for custom setup
        // Database is automatically created by Testcontainers before this hook runs
    }

    /**
     * Hook executed after each test method. Override in subclasses if custom teardown is needed. Default implementation
     * does nothing.
     *
     * <p>
     * Note: If using @TestTransaction, database changes are automatically rolled back. Manual cleanup is usually not
     * required.
     */
    @AfterEach
    protected void tearDown() {
        // Subclasses can override for custom teardown
        // Database is automatically rolled back by @TestTransaction (if used)
    }

    /**
     * Asserts that an entity with the given ID exists in the database.
     *
     * <p>
     * Uses reflection to call the entity class's findById() static method. Fails the test if entity is not found.
     *
     * @param entityClass
     *            the entity class (must extend PanacheEntityBase)
     * @param id
     *            the entity ID (Long)
     * @param <T>
     *            entity type parameter
     * @throws AssertionError
     *             if entity does not exist
     */
    protected <T extends PanacheEntityBase> void assertEntityExists(Class<T> entityClass, Long id) {
        assertNotNull(id, "Entity ID must not be null");
        T entity = findEntityById(entityClass, id);
        assertNotNull(entity, "Entity of type " + entityClass.getSimpleName() + " with id " + id + " should exist");
    }

    /**
     * Asserts that an entity with the given UUID exists in the database.
     *
     * <p>
     * Uses reflection to call the entity class's findById() static method. Fails the test if entity is not found.
     *
     * @param entityClass
     *            the entity class (must extend PanacheEntityBase)
     * @param id
     *            the entity ID (UUID)
     * @param <T>
     *            entity type parameter
     * @throws AssertionError
     *             if entity does not exist
     */
    protected <T extends PanacheEntityBase> void assertEntityExists(Class<T> entityClass, UUID id) {
        assertNotNull(id, "Entity ID must not be null");
        T entity = findEntityById(entityClass, id);
        assertNotNull(entity, "Entity of type " + entityClass.getSimpleName() + " with id " + id + " should exist");
    }

    /**
     * Asserts that an entity with the given ID has been soft-deleted (deletedAt IS NOT NULL).
     *
     * <p>
     * This method uses reflection to access the deletedAt field on the entity. The entity must have a field named
     * "deletedAt" of type Instant for soft deletion tracking.
     *
     * @param entityClass
     *            the entity class (must extend PanacheEntityBase)
     * @param id
     *            the entity ID (Long)
     * @param <T>
     *            entity type parameter
     * @throws AssertionError
     *             if entity does not exist or deletedAt is null
     */
    protected <T extends PanacheEntityBase> void assertEntityDeleted(Class<T> entityClass, Long id) {
        assertNotNull(id, "Entity ID must not be null");
        T entity = findEntityById(entityClass, id);
        assertNotNull(entity, "Entity of type " + entityClass.getSimpleName() + " with id " + id + " should exist");

        Instant deletedAt = getDeletedAtField(entity);
        assertNotNull(deletedAt, "Entity of type " + entityClass.getSimpleName() + " with id " + id
                + " should be soft-deleted (deletedAt should not be null)");
    }

    /**
     * Asserts that an entity with the given UUID has been soft-deleted (deletedAt IS NOT NULL).
     *
     * <p>
     * This method uses reflection to access the deletedAt field on the entity. The entity must have a field named
     * "deletedAt" of type Instant for soft deletion tracking.
     *
     * @param entityClass
     *            the entity class (must extend PanacheEntityBase)
     * @param id
     *            the entity ID (UUID)
     * @param <T>
     *            entity type parameter
     * @throws AssertionError
     *             if entity does not exist or deletedAt is null
     */
    protected <T extends PanacheEntityBase> void assertEntityDeleted(Class<T> entityClass, UUID id) {
        assertNotNull(id, "Entity ID must not be null");
        T entity = findEntityById(entityClass, id);
        assertNotNull(entity, "Entity of type " + entityClass.getSimpleName() + " with id " + id + " should exist");

        Instant deletedAt = getDeletedAtField(entity);
        assertNotNull(deletedAt, "Entity of type " + entityClass.getSimpleName() + " with id " + id
                + " should be soft-deleted (deletedAt should not be null)");
    }

    /**
     * Finds an entity by ID using reflection to call the entity class's static findById() method.
     *
     * <p>
     * This helper method works around Panache's static method enhancement by calling the entity class's findById()
     * method directly via reflection.
     *
     * @param entityClass
     *            the entity class
     * @param id
     *            the entity ID (Long or UUID)
     * @param <T>
     *            entity type parameter
     * @return the entity instance, or null if not found
     * @throws IllegalStateException
     *             if the entity class does not have a findById() method
     */
    @SuppressWarnings("unchecked")
    private <T extends PanacheEntityBase> T findEntityById(Class<T> entityClass, Object id) {
        try {
            Method findByIdMethod = entityClass.getMethod("findById", Object.class);
            return (T) findByIdMethod.invoke(null, id);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Cannot invoke findById() on entity " + entityClass.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves the deletedAt field value from an entity using reflection.
     *
     * <p>
     * This is a helper method for assertEntityDeleted(). Throws IllegalStateException if the entity does not have a
     * deletedAt field.
     *
     * @param entity
     *            the entity instance
     * @param <T>
     *            entity type parameter
     * @return the deletedAt timestamp, or null if not deleted
     * @throws IllegalStateException
     *             if entity class does not have a deletedAt field
     */
    private <T extends PanacheEntityBase> Instant getDeletedAtField(T entity) {
        try {
            Field deletedAtField = entity.getClass().getField("deletedAt");
            deletedAtField.setAccessible(true);
            return (Instant) deletedAtField.get(entity);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("Entity " + entity.getClass().getSimpleName()
                    + " does not have a public 'deletedAt' field for soft-delete tracking", e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Cannot access deletedAt field on entity " + entity.getClass().getSimpleName(), e);
        } catch (ClassCastException e) {
            throw new IllegalStateException(
                    "Entity " + entity.getClass().getSimpleName() + " deletedAt field is not of type Instant", e);
        }
    }
}
