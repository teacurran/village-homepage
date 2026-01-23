package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * OAuth state token for CSRF protection.
 *
 * <p>
 * State tokens are cryptographically random UUID v4 values with a 5-minute TTL. They are single-use and deleted after
 * validation to prevent replay attacks.
 *
 * <p>
 * See Iteration I3 Plan and Policy P9 (CSRF Protection) for security requirements.
 */
@Entity
@Table(
        name = "oauth_states")
@NamedQuery(
        name = OAuthState.QUERY_FIND_BY_STATE_AND_PROVIDER,
        query = "SELECT o FROM OAuthState o WHERE o.state = :state AND o.provider = :provider "
                + "AND o.expiresAt > CURRENT_TIMESTAMP")
public class OAuthState extends PanacheEntityBase {

    public static final String QUERY_FIND_BY_STATE_AND_PROVIDER = "OAuthState.findByStateAndProvider";

    @Id
    @GeneratedValue
    public UUID id;

    @Column(
            nullable = false,
            unique = true)
    public String state;

    @Column(
            name = "session_id",
            nullable = false)
    public String sessionId;

    @Column(
            nullable = false)
    public String provider;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    @Column(
            name = "expires_at",
            nullable = false)
    public Instant expiresAt;

    /**
     * Find an OAuth state by state token and provider, rejecting expired tokens.
     *
     * @param state
     *            the state token (UUID v4)
     * @param provider
     *            the OAuth provider ('google', 'facebook', 'apple')
     * @return the OAuthState if found and not expired, empty otherwise
     */
    public static Optional<OAuthState> findByStateAndProvider(String state, String provider) {
        return find("#" + QUERY_FIND_BY_STATE_AND_PROVIDER,
                io.quarkus.panache.common.Parameters.with("state", state).and("provider", provider))
                .firstResultOptional();
    }
}
