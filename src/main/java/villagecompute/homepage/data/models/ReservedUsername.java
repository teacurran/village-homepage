package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ReservedUsername entity for namespace protection and impersonation prevention.
 *
 * <p>
 * Stores usernames reserved for system use, features, admin roles, and common terms. Profile creation validates against
 * this table to prevent conflicts and impersonation.
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (UUID, PK) - Primary identifier</li>
 * <li>{@code username} (TEXT, unique) - Reserved username (lowercase)</li>
 * <li>{@code reason} (TEXT) - Reason for reservation (category + description)</li>
 * <li>{@code reserved_at} (TIMESTAMPTZ) - Timestamp when reserved</li>
 * </ul>
 *
 * <p>
 * <b>Reserved Categories:</b>
 * <ul>
 * <li>System: admin, api, cdn, www, assets, static, public</li>
 * <li>Features: good-sites, marketplace, calendar, directory, search</li>
 * <li>Admin roles: support, ops, moderator, root, superuser</li>
 * <li>Common: help, about, contact, terms, privacy, blog, news</li>
 * </ul>
 *
 * <p>
 * <b>Enforcement:</b> Username validation in {@link UserProfile#normalizeUsername(String)} checks this table before
 * allowing profile creation. Admins can add/remove reserved names via admin API.
 *
 * @see UserProfile for username validation integration
 */
@Entity
@Table(
        name = "reserved_usernames")
@NamedQuery(
        name = ReservedUsername.QUERY_FIND_BY_USERNAME,
        query = ReservedUsername.JPQL_FIND_BY_USERNAME)
@NamedQuery(
        name = ReservedUsername.QUERY_FIND_BY_REASON,
        query = ReservedUsername.JPQL_FIND_BY_REASON)
public class ReservedUsername extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(ReservedUsername.class);

    public static final String JPQL_FIND_BY_USERNAME = "SELECT ru FROM ReservedUsername ru WHERE ru.username = :username";
    public static final String QUERY_FIND_BY_USERNAME = "ReservedUsername.findByUsername";

    public static final String JPQL_FIND_BY_REASON = "SELECT ru FROM ReservedUsername ru WHERE ru.reason LIKE :reasonPattern";
    public static final String QUERY_FIND_BY_REASON = "ReservedUsername.findByReason";

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            nullable = false)
    public String username;

    @Column(
            nullable = false)
    public String reason;

    @Column(
            name = "reserved_at",
            nullable = false)
    public Instant reservedAt;

    /**
     * Checks if a username is reserved.
     *
     * @param username
     *            username to check (case-insensitive)
     * @return true if username is reserved, false otherwise
     */
    public static boolean isReserved(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        String normalized = username.trim().toLowerCase();
        return count("username = ?1", normalized) > 0;
    }

    /**
     * Finds a reserved username by exact match.
     *
     * @param username
     *            username to find (case-insensitive)
     * @return Optional containing the reserved username if found
     */
    public static Optional<ReservedUsername> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String normalized = username.trim().toLowerCase();
        return find(JPQL_FIND_BY_USERNAME, io.quarkus.panache.common.Parameters.with("username", normalized))
                .firstResultOptional();
    }

    /**
     * Finds all reserved usernames by reason category (partial match).
     *
     * @param reasonPattern
     *            SQL LIKE pattern (e.g., "System:%")
     * @return list of reserved usernames matching the reason pattern
     */
    public static List<ReservedUsername> findByReason(String reasonPattern) {
        if (reasonPattern == null || reasonPattern.isBlank()) {
            return List.of();
        }
        return find(JPQL_FIND_BY_REASON, io.quarkus.panache.common.Parameters.with("reasonPattern", reasonPattern))
                .list();
    }

    /**
     * Lists all reserved usernames ordered by username.
     *
     * @return list of all reserved usernames
     */
    public static List<ReservedUsername> listAll() {
        return find("ORDER BY username").list();
    }

    /**
     * Adds a new reserved username.
     *
     * @param username
     *            username to reserve (will be normalized to lowercase)
     * @param reason
     *            reason for reservation
     * @return persisted reserved username
     * @throws IllegalArgumentException
     *             if username already reserved
     */
    public static ReservedUsername reserve(String username, String reason) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username cannot be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Reason cannot be blank");
        }

        String normalized = username.trim().toLowerCase();

        // Check if already reserved
        if (isReserved(normalized)) {
            throw new IllegalArgumentException("Username already reserved: " + normalized);
        }

        ReservedUsername reserved = new ReservedUsername();
        reserved.username = normalized;
        reserved.reason = reason;
        reserved.reservedAt = Instant.now();

        QuarkusTransaction.requiringNew().run(() -> reserved.persist());
        LOG.infof("Reserved username: %s (reason: %s)", normalized, reason);
        return reserved;
    }

    /**
     * Removes a reserved username (unreserves it).
     *
     * @param username
     *            username to unreserve
     * @return true if username was unreserved, false if not found
     */
    public static boolean unreserve(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }

        String normalized = username.trim().toLowerCase();
        Optional<ReservedUsername> reservedOpt = findByUsername(normalized);

        if (reservedOpt.isEmpty()) {
            LOG.warnf("Cannot unreserve username - not found: %s", normalized);
            return false;
        }

        ReservedUsername reserved = reservedOpt.get();
        QuarkusTransaction.requiringNew().run(() -> reserved.delete());
        LOG.infof("Unreserved username: %s", normalized);
        return true;
    }

    /**
     * Seeds default reserved usernames from predefined lists.
     *
     * <p>
     * This method is called during application bootstrap or via CLI to populate the initial set of reserved names.
     * Skips usernames that are already reserved.
     */
    public static void seedDefaults() {
        LOG.info("Seeding default reserved usernames...");

        int seeded = 0;

        // System reserved names
        String[] systemNames = {"admin", "api", "cdn", "www", "assets", "static", "public", "app", "web", "mail", "ftp",
                "ssh", "vpn", "proxy"};
        for (String name : systemNames) {
            if (!isReserved(name)) {
                reserve(name, "System: Reserved for infrastructure");
                seeded++;
            }
        }

        // Feature reserved names
        String[] featureNames = {"good-sites", "goodsites", "marketplace", "calendar", "directory", "search", "feed",
                "feeds", "rss", "widget", "widgets"};
        for (String name : featureNames) {
            if (!isReserved(name)) {
                reserve(name, "Feature: Reserved for application features");
                seeded++;
            }
        }

        // Admin role reserved names
        String[] adminNames = {"support", "ops", "moderator", "mod", "root", "superuser", "administrator", "sysadmin",
                "webmaster"};
        for (String name : adminNames) {
            if (!isReserved(name)) {
                reserve(name, "Admin: Reserved for admin roles");
                seeded++;
            }
        }

        // Common reserved names
        String[] commonNames = {"help", "about", "contact", "terms", "privacy", "blog", "news", "faq", "status",
                "legal", "security", "login", "logout", "signup", "signin", "register", "account", "profile",
                "settings", "dashboard"};
        for (String name : commonNames) {
            if (!isReserved(name)) {
                reserve(name, "Common: Reserved for standard pages/features");
                seeded++;
            }
        }

        LOG.infof("Seeded %d reserved usernames", seeded);
    }

    /**
     * Counts total reserved usernames.
     *
     * @return count of reserved usernames
     */
    public static long countAll() {
        return count();
    }
}
