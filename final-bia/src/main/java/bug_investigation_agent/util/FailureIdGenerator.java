package bug_investigation_agent.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern EXCEPTION_CLASS =
        Pattern.compile("([A-Za-z_][A-Za-z0-9_.$]*(?:Exception|Error))\\b");
    private static final Pattern UUID =
        Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<!\\d)\\d{3,}(?!\\d)");
    private static final Pattern XPATH_INDEX = Pattern.compile("\\[\\d+\\]");

    private FailureIdGenerator() {
    }

    /**
     * Generates a stable 16-hex-character id from canonicalized failure identity signals. This
     * intentionally ignores scenario/feature labels, which tend to vary between suites/runs, and
     * normalizes volatile tokens (ids/indexes/uuids/large numbers) from the error signature.
     */
    public static String generate(String scenario, String feature, String failedStep, String error) {
        String basis = normalizeStep(failedStep) + "|"
                + normalizeExceptionType(error) + "|"
                + normalizeErrorSignature(error);
        return sha256Hex(basis).substring(0, 16);
    }

    /**
     * Legacy id algorithm retained for backward-compatible lookup of rows already persisted with
     * the previous strategy.
     */
    public static String generateLegacy(String scenario, String feature, String failedStep, String error) {
        String basis = normalize(scenario) + "|"
                + normalize(feature) + "|"
                + normalize(failedStep) + "|"
                + normalize(error);
        return sha256Hex(basis).substring(0, 16);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private static String normalizeStep(String value) {
        return normalize(value);
    }

    private static String normalizeExceptionType(String error) {
        if (error == null || error.isBlank()) {
            return "";
        }
        Matcher matcher = EXCEPTION_CLASS.matcher(error);
        if (!matcher.find()) {
            return "";
        }
        String fullyQualified = matcher.group(1);
        int lastDot = fullyQualified.lastIndexOf('.');
        String simple = lastDot >= 0 ? fullyQualified.substring(lastDot + 1) : fullyQualified;
        return normalize(simple);
    }

    private static String normalizeErrorSignature(String error) {
        if (error == null) {
            return "";
        }
        String normalized = normalize(error);
        normalized = UUID.matcher(normalized).replaceAll("<uuid>");
        normalized = XPATH_INDEX.matcher(normalized).replaceAll("[#]");
        normalized = LONG_NUMBER.matcher(normalized).replaceAll("<num>");
        return normalized;
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

