package villagecompute.homepage.integration.payments;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Verifies Stripe webhook signatures using HMAC-SHA256.
 *
 * <p>
 * <b>Security Critical:</b> All webhook payloads MUST be verified before processing to prevent spoofing attacks.
 * Attackers could send fake webhook events to activate listings without payment.
 *
 * <p>
 * Stripe webhook signature format:
 *
 * <pre>
 * Stripe-Signature: t=1614556800,v1=abc123...,v0=def456...
 * </pre>
 *
 * <p>
 * Verification algorithm:
 * <ol>
 * <li>Extract timestamp and v1 signature from header</li>
 * <li>Construct signed payload: timestamp + "." + raw_body</li>
 * <li>Compute HMAC-SHA256 of signed payload using webhook secret</li>
 * <li>Compare computed signature with v1 signature (constant-time comparison)</li>
 * <li>Check timestamp is within tolerance (5 minutes) to prevent replay attacks</li>
 * </ol>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P3: Marketplace payment & fraud policy (chargeback handling, transaction security)</li>
 * </ul>
 *
 * @see <a href="https://stripe.com/docs/webhooks/signatures">Stripe Webhook Signatures</a>
 */
@ApplicationScoped
public class StripeWebhookVerifier {

    private static final Logger LOG = Logger.getLogger(StripeWebhookVerifier.class);

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final long SIGNATURE_MAX_AGE_SECONDS = 300; // 5 minutes

    @ConfigProperty(
            name = "stripe.webhook-secret")
    String webhookSecret;

    /**
     * Verifies the Stripe webhook signature and timestamp.
     *
     * @param payload
     *            raw webhook payload (JSON string)
     * @param signatureHeader
     *            value of Stripe-Signature header
     * @return true if signature is valid and timestamp is within tolerance
     */
    public boolean verifySignature(String payload, String signatureHeader) {
        if (payload == null || signatureHeader == null) {
            LOG.warn("Webhook verification failed: null payload or signature");
            return false;
        }

        try {
            // Parse signature header: t=1614556800,v1=abc123...,v0=def456...
            String[] parts = signatureHeader.split(",");
            long timestamp = -1;
            String v1Signature = null;

            for (String part : parts) {
                String[] keyValue = part.split("=", 2);
                if (keyValue.length != 2) {
                    continue;
                }

                if ("t".equals(keyValue[0])) {
                    timestamp = Long.parseLong(keyValue[1]);
                } else if ("v1".equals(keyValue[0])) {
                    v1Signature = keyValue[1];
                }
            }

            if (timestamp == -1 || v1Signature == null) {
                LOG.warnf("Webhook verification failed: missing timestamp or v1 signature in header: %s",
                        signatureHeader);
                return false;
            }

            // Check timestamp tolerance (prevent replay attacks)
            long currentTimestamp = System.currentTimeMillis() / 1000;
            if (Math.abs(currentTimestamp - timestamp) > SIGNATURE_MAX_AGE_SECONDS) {
                LOG.warnf("Webhook verification failed: timestamp %d outside tolerance (%d seconds)", timestamp,
                        SIGNATURE_MAX_AGE_SECONDS);
                return false;
            }

            // Construct signed payload
            String signedPayload = timestamp + "." + payload;

            // Compute expected signature
            String expectedSignature = computeHmacSha256(signedPayload, webhookSecret);

            // Constant-time comparison to prevent timing attacks
            boolean isValid = MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8),
                    v1Signature.getBytes(StandardCharsets.UTF_8));

            if (!isValid) {
                LOG.warnf("Webhook verification failed: signature mismatch");
            }

            return isValid;

        } catch (Exception e) {
            LOG.errorf(e, "Webhook verification failed with exception");
            return false;
        }
    }

    /**
     * Computes HMAC-SHA256 signature of payload using secret key.
     *
     * @param payload
     *            data to sign
     * @param secret
     *            HMAC secret key
     * @return hex-encoded signature
     */
    private String computeHmacSha256(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKeySpec);

            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));

            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();

        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256", e);
        }
    }
}
