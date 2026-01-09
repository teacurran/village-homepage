package villagecompute.homepage.services;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.NewCookie;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.testing.H2TestResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(H2TestResource.class)
class AuthIdentityServiceTest {

    @Inject
    AuthIdentityService authIdentityService;

    @Test
    void anonymousCookieHasSecurityAttributes() {
        NewCookie cookie = authIdentityService.issueAnonymousCookie();

        assertEquals("vu_anon_id", cookie.getName());
        assertTrue(cookie.isHttpOnly(), "Cookie must be HttpOnly");
        assertTrue(cookie.isSecure(), "Cookie must be secure");
        assertEquals(NewCookie.SameSite.LAX, cookie.getSameSite());
        assertEquals(31536000, cookie.getMaxAge());
    }

    @Test
    void bootstrapGuardBlocksSecondAdmin() {
        AuthIdentityService.BootstrapValidationResult initial = authIdentityService
                .validateBootstrapToken("test-bootstrap-token", "127.0.0.1");
        assertEquals(AuthIdentityService.BootstrapValidationResult.SUCCESS, initial);

        AuthIdentityService.BootstrapSession session = authIdentityService.createSuperuser("bootstrap@example.com",
                "google", "provider-1");
        assertNotNull(session);
        assertNotNull(session.jwt());

        AuthIdentityService.BootstrapValidationResult second = authIdentityService
                .validateBootstrapToken("test-bootstrap-token", "127.0.0.1");
        assertEquals(AuthIdentityService.BootstrapValidationResult.ADMIN_EXISTS, second);
    }
}
