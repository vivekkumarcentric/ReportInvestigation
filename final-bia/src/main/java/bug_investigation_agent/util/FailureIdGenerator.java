package bug_investigation_agent.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes a stable, deterministic identifier for a failure based on its content
 * (scenario/feature/failed step/error message), so the SAME failure re-analyzed later (e.g. after
 * a page refresh, or the next time the same report is re-uploaded) resolves to the SAME
 * {@code failureId}. This is what allows human classification corrections to persist and be
 * re-applied across investigations of the same failure.
 *
 * <p>This is a simple content hash - it intentionally does NOT do fuzzy/similarity matching across
 * different failures. Similarity-based historical lookup across DIFFERENT failures is a separate,
 * not-yet-implemented feature.</p>
 */
public final class FailureIdGenerator {

    private FailureIdGenerator() {
    }

    /**
     * Generates a stable 16-hex-character id from the given failure identity fields.
     */
    public static String generate(String scenario, String feature, String failedStep, String error) {
        String basis =
                normalize(scenario) + "|" + normalize(feature) + "|" + normalize(failedStep) + "|" + normalize(error);
        return sha256Hex(basis).substring(0, 16);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed available on every JVM; this should never happen.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

