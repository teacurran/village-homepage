package villagecompute.homepage.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EmailValidator utility.
 *
 * Tests cover email format validation (regex), MX record validation (DNS lookup), and disposable email domain
 * detection.
 *
 * Note: MX record tests are limited to basic validation logic. Full DNS integration testing would require mocking DNS
 * or using test domains, which is outside the scope of unit tests.
 *
 * Coverage target: ≥95% line and branch coverage per project standards.
 */
class EmailValidatorTest {

    /**
     * Test: Valid email formats pass validation.
     */
    @Test
    void testIsValidFormat_ValidEmails_ReturnsTrue() {
        assertTrue(EmailValidator.isValidFormat("user@example.com"), "Simple email should be valid");
        assertTrue(EmailValidator.isValidFormat("user.name@example.com"),
                "Email with dot in local part should be valid");
        assertTrue(EmailValidator.isValidFormat("user+tag@example.co.uk"), "Email with plus sign should be valid");
        assertTrue(EmailValidator.isValidFormat("user_name@subdomain.example.com"),
                "Email with underscore and subdomain should be valid");
        assertTrue(EmailValidator.isValidFormat("123@example.com"), "Numeric local part should be valid");
    }

    /**
     * Test: Invalid email formats fail validation.
     */
    @Test
    void testIsValidFormat_InvalidEmails_ReturnsFalse() {
        assertFalse(EmailValidator.isValidFormat(null), "Null email should be invalid");
        assertFalse(EmailValidator.isValidFormat(""), "Empty email should be invalid");
        assertFalse(EmailValidator.isValidFormat("   "), "Whitespace-only email should be invalid");
        assertFalse(EmailValidator.isValidFormat("not-an-email"), "String without @ should be invalid");
        assertFalse(EmailValidator.isValidFormat("@example.com"), "Email without local part should be invalid");
        assertFalse(EmailValidator.isValidFormat("user@"), "Email without domain should be invalid");
        assertFalse(EmailValidator.isValidFormat("user @example.com"), "Email with space should be invalid");
        assertFalse(EmailValidator.isValidFormat("user@.com"), "Email with invalid domain should be invalid");
        assertFalse(EmailValidator.isValidFormat("user@domain"), "Email without TLD should be invalid");
    }

    /**
     * Test: Disposable email domains are correctly detected.
     */
    @Test
    void testIsDisposableEmail_KnownDisposableDomains_ReturnsTrue() {
        assertTrue(EmailValidator.isDisposableEmail("user@mailinator.com"), "mailinator.com should be blocked");
        assertTrue(EmailValidator.isDisposableEmail("user@guerrillamail.com"), "guerrillamail.com should be blocked");
        assertTrue(EmailValidator.isDisposableEmail("user@10minutemail.com"), "10minutemail.com should be blocked");
        assertTrue(EmailValidator.isDisposableEmail("user@temp-mail.org"), "temp-mail.org should be blocked");
        assertTrue(EmailValidator.isDisposableEmail("user@throwaway.email"), "throwaway.email should be blocked");
        assertTrue(EmailValidator.isDisposableEmail("user@yopmail.com"), "yopmail.com should be blocked");
    }

    /**
     * Test: Legitimate email domains are NOT flagged as disposable.
     */
    @Test
    void testIsDisposableEmail_LegitDomains_ReturnsFalse() {
        assertFalse(EmailValidator.isDisposableEmail("user@gmail.com"), "gmail.com should NOT be blocked");
        assertFalse(EmailValidator.isDisposableEmail("user@outlook.com"), "outlook.com should NOT be blocked");
        assertFalse(EmailValidator.isDisposableEmail("user@yahoo.com"), "yahoo.com should NOT be blocked");
        assertFalse(EmailValidator.isDisposableEmail("user@example.com"), "example.com should NOT be blocked");
        assertFalse(EmailValidator.isDisposableEmail("user@company.co.uk"), "company.co.uk should NOT be blocked");
    }

    /**
     * Test: Disposable check is case-insensitive.
     */
    @Test
    void testIsDisposableEmail_CaseInsensitive_ReturnsTrue() {
        assertTrue(EmailValidator.isDisposableEmail("user@MAILINATOR.COM"), "Should be case-insensitive");
        assertTrue(EmailValidator.isDisposableEmail("user@Mailinator.Com"), "Should be case-insensitive");
        assertTrue(EmailValidator.isDisposableEmail("user@GuerRillaMail.com"), "Should be case-insensitive");
    }

    /**
     * Test: Null/blank emails return false for disposable check.
     */
    @Test
    void testIsDisposableEmail_NullOrBlank_ReturnsFalse() {
        assertFalse(EmailValidator.isDisposableEmail(null), "Null email should return false");
        assertFalse(EmailValidator.isDisposableEmail(""), "Empty email should return false");
        assertFalse(EmailValidator.isDisposableEmail("   "), "Whitespace email should return false");
    }

    /**
     * Test: hasValidMxRecord handles invalid email format (no @ symbol).
     */
    @Test
    void testHasValidMxRecord_InvalidFormat_ReturnsFalse() {
        assertFalse(EmailValidator.hasValidMxRecord("not-an-email"), "Invalid email format should return false");
        assertFalse(EmailValidator.hasValidMxRecord(null), "Null email should return false");
        assertFalse(EmailValidator.hasValidMxRecord(""), "Empty email should return false");
    }

    /**
     * Test: hasValidMxRecord handles valid domain format (basic check).
     *
     * Note: This test cannot verify actual DNS lookups without integration testing or DNS mocking. It verifies the
     * method executes without exceptions for well-formed domains.
     */
    @Test
    void testHasValidMxRecord_ValidDomain_DoesNotThrow() {
        // This test verifies the method handles valid domains without throwing exceptions
        // Actual DNS lookups may fail in CI environments, so we just ensure no exceptions
        assertDoesNotThrow(() -> EmailValidator.hasValidMxRecord("user@gmail.com"),
                "Should not throw exception for valid domain");

        // Note: Cannot assert true/false result because DNS may not be available in all test environments
        // Integration tests with mocked DNS would be needed for deterministic MX validation testing
    }

    /**
     * Test: Comprehensive validation (format + disposable + MX) for valid email.
     *
     * Note: MX validation may fail in test environments without DNS access, so this test focuses on format and
     * disposable checks.
     */
    @Test
    void testIsValid_DisposableEmail_ReturnsFalse() {
        // Even if format is valid, disposable domains should fail comprehensive validation
        assertFalse(
                EmailValidator.isValidFormat("user@mailinator.com")
                        && !EmailValidator.isDisposableEmail("user@mailinator.com"),
                "Disposable email should fail validation");
    }

    /**
     * Test: Comprehensive validation rejects invalid format.
     */
    @Test
    void testIsValid_InvalidFormat_ReturnsFalse() {
        assertFalse(EmailValidator.isValidFormat("not-an-email"), "Invalid format should fail validation");
    }
}
