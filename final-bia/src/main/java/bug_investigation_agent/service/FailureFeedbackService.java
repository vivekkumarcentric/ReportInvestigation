package bug_investigation_agent.service;

import bug_investigation_agent.model.FailureFeedback;
import bug_investigation_agent.model.request.InvestigationRequest;
import bug_investigation_agent.model.response.FailureClassificationResponse;
import bug_investigation_agent.model.response.InvestigationResponse;
import bug_investigation_agent.repository.FailureFeedbackRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Human classification feedback: persists the AI's classification baseline for a failure, and
 * allows a human to correct it. The AI's original classification is never overwritten; only the
 * human's correction is stored/updated separately.
 *
 * <p>{@link #getClassification(String)} is an exact match on {@code failureId} (a stable content
 * hash of the SAME failure - see {@link bug_investigation_agent.util.FailureIdGenerator}) and
 * remains the first/primary lookup. {@link #findHistoricalMatch(InvestigationRequest)} is a
 * separate, secondary lookup used when no exact match exists: it reuses a human classification
 * across DIFFERENT failures (e.g. the same logical failure re-appearing in a different report)
 * by comparing {@code exception_type} plus either {@code locator} or {@code normalized_step} -
 * fields already extracted/stored by {@link #recordAiResult}.</p>
 */
@Service
public class FailureFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FailureFeedbackService.class);

    public static final String SOURCE_AI = "AI";
    public static final String SOURCE_HISTORICAL = "HISTORICAL"; // cross-report similarity match (see findHistoricalMatch)
    public static final String SOURCE_AI_ENRICHED = "AI_ENRICHED";
    public static final String SOURCE_HUMAN_CORRECTED = "HUMAN_CORRECTED";

    /** Must match the classification values the AI is instructed to return (see
     *  {@link bug_investigation_agent.prompt.InvestigationPromptBuilder}). */
    private static final Set<String> VALID_CLASSIFICATIONS = new LinkedHashSet<>(Set.of(
            "AUTOMATION_ISSUE", "APPLICATION_ISSUE", "API_ISSUE", "DATA_ISSUE",
            "ENVIRONMENT_ISSUE", "NETWORK_ISSUE", "UNKNOWN"));

    private static final Pattern GHERKIN_KEYWORD =
            Pattern.compile("^(given|when|then|and|but)\\s+", Pattern.CASE_INSENSITIVE);

    private static final Pattern EXCEPTION_CLASS =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_.$]*(?:Exception|Error))\\b");

    private static final Pattern[] LOCATOR_PATTERNS = {
            Pattern.compile("resource-id\\s*[=:]\\s*['\"]?([\\w.:/-]+)['\"]?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("By\\.xpath\\s*:?\\s*(\"[^\"]+\"|'[^']+'|[^,)\\n]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("By\\.id\\s*:?\\s*(\"[^\"]+\"|'[^']+'|[^,)\\n]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("By\\.cssSelector\\s*:?\\s*(\"[^\"]+\"|'[^']+'|[^,)\\n]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("accessibilityId\\s*[=:]\\s*['\"]?([\\w.:/-]+)['\"]?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:test-id|data-testid)\\s*[=:]\\s*['\"]?([\\w.:/-]+)['\"]?", Pattern.CASE_INSENSITIVE)
    };

    private final FailureFeedbackRepository repository;
    private final ObjectMapper objectMapper;

    public FailureFeedbackService(FailureFeedbackRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public static boolean isValidClassification(String value) {
        return value != null && VALID_CLASSIFICATIONS.contains(value.trim().toUpperCase());
    }

    public static Set<String> validClassifications() {
        return VALID_CLASSIFICATIONS;
    }

    /**
     * Records/updates the AI-generated baseline for a failure. Safe to call repeatedly for the
     * same {@code failureId} (e.g. every time the same failure is re-investigated) - it never
     * touches an existing {@code humanClassification}. Any failure while persisting is logged and
     * swallowed so that a database problem never breaks the core investigation flow.
     */
    public void recordAiResult(String failureId, InvestigationRequest request, InvestigationResponse response) {
        if (failureId == null || failureId.isBlank()) {
            return;
        }
        try {
            FailureFeedback existing = repository.findByFailureId(failureId).orElse(null);
            if (existing != null) {
                log.info("Updating existing failure record: {}", failureId);
            }
            FailureFeedback feedback = existing != null ? existing : new FailureFeedback();
            feedback.setFailureId(failureId);

            String scenario = request == null
                    ? null
                    : (isBlank(request.getScenario()) ? request.getTestName() : request.getScenario());
            feedback.setScenarioName(scenario);
            feedback.setFeatureName(request == null ? null : request.getFeature());

            String failedStep = request == null ? null : request.getFailedStep();
            feedback.setFailedStepLine(failedStep);
            feedback.setNormalizedStep(normalizeStep(failedStep));

            String error = request == null ? null : request.getError();
            feedback.setNormalizedError(normalizeError(error));

            String stackTrace = request == null ? null : request.getStackTrace();
            String combined = nullToEmpty(error) + "\n" + nullToEmpty(stackTrace);
            feedback.setExceptionType(extractExceptionType(combined));
            feedback.setLocator(extractLocator(combined));
            feedback.setStackTracePattern(extractStackTracePattern(stackTrace));

            feedback.setAiClassification(response == null ? null : response.getClassification());
            feedback.setRootCause(response == null ? null : response.getRootCause());
            feedback.setRootCauseType(response == null ? null : response.getRootCauseType());
            feedback.setSeverity(response == null ? null : response.getSeverity());
            feedback.setRecommendedAction(response == null ? null : response.getRecommendedAction());
            feedback.setSuggestedFix(response == null ? null : response.getSuggestedFix());
            feedback.setSimilarPatterns(response == null ? null : response.getSimilarPatterns());
            feedback.setScreenshotObservation(response == null ? null : response.getScreenshotObservation());
            feedback.setScreenshotHash(calculateScreenshotHash(request == null ? null : request.getFailureImage()));
            feedback.setEvidenceJson(toJsonArray(response == null ? null : response.getEvidence()));
            feedback.setMissingEvidenceJson(toJsonArray(response == null ? null : response.getMissingEvidence()));
            feedback.setPreventionTipsJson(toJsonArray(response == null ? null : response.getPreventionTips()));
            feedback.setStepsToReproduceJson(toJsonArray(response == null ? null : response.getStepsToReproduce()));
            feedback.setSource(response == null ? null : response.getSource());
            feedback.setConfidence(response == null ? null : response.getConfidence());
            // Intentionally NOT touching feedback.humanClassification here.

            Instant now = Instant.now();
            if (existing == null) {
                feedback.setCreatedAt(now);
            }
            feedback.setUpdatedAt(now);

            repository.upsert(feedback);
        } catch (Exception e) {
            log.warn("Failed to persist AI feedback baseline for failureId={}: {}", failureId, e.getMessage());
        }
    }

    /**
     * Saves (or updates) the human classification correction for a failure.
     *
     * @throws IllegalArgumentException if {@code failureId} is blank, {@code humanClassification}
     *                                   is missing/blank, or is not one of the valid classification
     *                                   values.
     */
    public FailureClassificationResponse saveHumanClassification(String failureId, String humanClassification) {
        if (failureId == null || failureId.isBlank()) {
            throw new IllegalArgumentException("failureId must not be blank");
        }
        if (humanClassification == null || humanClassification.isBlank()) {
            throw new IllegalArgumentException("humanClassification is required");
        }
        String normalized = humanClassification.trim().toUpperCase();
        if (!VALID_CLASSIFICATIONS.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Invalid classification '" + humanClassification + "'. Valid values: " + VALID_CLASSIFICATIONS);
        }

        FailureFeedback feedback = repository.findByFailureId(failureId).orElse(null);
        Instant now = Instant.now();
        if (feedback == null) {
            feedback = new FailureFeedback();
            feedback.setFailureId(failureId);
            feedback.setCreatedAt(now);
        }
        feedback.setHumanClassification(normalized);
        feedback.setUpdatedAt(now);

        repository.upsert(feedback);

        return toResponse(feedback);
    }

    public Optional<FailureClassificationResponse> getClassification(String failureId) {
        if (failureId == null || failureId.isBlank()) {
            return Optional.empty();
        }
        return repository.findByFailureId(failureId).map(this::toResponse);
    }

    /**
     * Returns the raw persisted feedback record for an exact {@code failureId} match.
     *
     * <p>This is used by {@link InvestigationService} to reuse previously saved failure details
     * without re-calling Ollama when the same failure appears again.</p>
     */
    public Optional<FailureFeedback> getFeedbackByFailureId(String failureId) {
        if (failureId == null || failureId.isBlank()) {
            return Optional.empty();
        }
        return repository.findByFailureId(failureId);
    }

    /**
     * Creates an alias row for a failure under a new id if needed, preserving the learned
     * classification context while rolling out a new id-generation strategy.
     */
    public void copyFeedbackToFailureId(FailureFeedback source, String targetFailureId) {
        if (source == null || targetFailureId == null || targetFailureId.isBlank()) {
            return;
        }
        if (targetFailureId.equals(source.getFailureId())) {
            return;
        }
        try {
            if (repository.findByFailureId(targetFailureId).isPresent()) {
                return;
            }
            FailureFeedback copy = new FailureFeedback();
            copy.setFailureId(targetFailureId);
            copy.setScenarioName(source.getScenarioName());
            copy.setFeatureName(source.getFeatureName());
            copy.setFailedStepLine(source.getFailedStepLine());
            copy.setNormalizedStep(source.getNormalizedStep());
            copy.setNormalizedError(source.getNormalizedError());
            copy.setExceptionType(source.getExceptionType());
            copy.setLocator(source.getLocator());
            copy.setStackTracePattern(source.getStackTracePattern());
            copy.setAiClassification(source.getAiClassification());
            copy.setHumanClassification(source.getHumanClassification());
            copy.setRootCause(source.getRootCause());
            copy.setRootCauseType(source.getRootCauseType());
            copy.setSeverity(source.getSeverity());
            copy.setRecommendedAction(source.getRecommendedAction());
            copy.setSuggestedFix(source.getSuggestedFix());
            copy.setSimilarPatterns(source.getSimilarPatterns());
            copy.setScreenshotObservation(source.getScreenshotObservation());
            copy.setScreenshotHash(source.getScreenshotHash());
            copy.setEvidenceJson(source.getEvidenceJson());
            copy.setMissingEvidenceJson(source.getMissingEvidenceJson());
            copy.setPreventionTipsJson(source.getPreventionTipsJson());
            copy.setStepsToReproduceJson(source.getStepsToReproduceJson());
            copy.setSource(source.getSource());
            copy.setConfidence(source.getConfidence());
            Instant now = Instant.now();
            copy.setCreatedAt(source.getCreatedAt() == null ? now : source.getCreatedAt());
            copy.setUpdatedAt(now);
            repository.upsert(copy);
        } catch (Exception e) {
            log.warn("Failed to copy feedback alias {} -> {}: {}",
                    source.getFailureId(), targetFailureId, e.getMessage());
        }
    }

    /**
     * Cross-report historical similarity match: reuses a human classification for a DIFFERENT
     * failure (different {@code failureId}) that is nonetheless the same underlying logical
     * failure re-appearing (e.g. in a re-run report). Only called by
     * {@link InvestigationService} as a fallback AFTER the exact {@code failureId} lookup
     * ({@link #getClassification(String)}) has already missed.
     *
     * <p>Matching rules (deterministic, no fuzzy/embedding matching):</p>
     * <ul>
     *   <li>{@code exception_type} must be extractable from this request AND match a candidate's
     *       {@code exception_type} (case-insensitive) - mandatory; without it, nothing is
     *       considered a match.</li>
     *   <li>If this request has a locator, the candidate's {@code locator} must match it
     *       (case-insensitive) - locator is the stronger signal when available.</li>
     *   <li>Otherwise (no locator on this request), the candidate's {@code normalized_step} must
     *       match this request's normalized failed step instead.</li>
     *   <li>Only candidates with a non-blank {@code human_classification} are ever considered
     *       (reusing an AI-only guess across different failures is never safe).</li>
     *   <li>If the matching candidates disagree on {@code human_classification} (more than one
     *       distinct value), the match is treated as ambiguous and NOT reused - the caller should
     *       fall through to Ollama instead.</li>
     * </ul>
     *
     * @return the matched historical classification (single, non-conflicting agreement across all
     *         matching candidates), or empty if no safe match exists.
     */
    public Optional<FailureClassificationResponse> findHistoricalMatch(InvestigationRequest request) {
        if (request == null) {
            return Optional.empty();
        }

        String combined = nullToEmpty(request.getError()) + "\n" + nullToEmpty(request.getStackTrace());
        String exceptionType = extractExceptionType(combined);
        if (exceptionType == null || exceptionType.isBlank()) {
            // Mandatory signal - without an exception type, refuse to guess a cross-report match.
            return Optional.empty();
        }

        String locator = extractLocator(combined);
        boolean hasLocator = locator != null && !locator.isBlank();
        boolean dynamicLocator = hasLocator && isLikelyDynamicLocator(locator);
        String normalizedRequestLocator = hasLocator ? normalizeLocatorForMatch(locator) : null;
        String normalizedStep = normalizeStep(request.getFailedStep());

        List<FailureFeedback> candidates;
        try {
            candidates = repository.findHumanClassifiedByExceptionType(exceptionType);
        } catch (Exception e) {
            log.warn("Failed to query historical candidates for exceptionType={}: {}",
                    exceptionType, e.getMessage());
            return Optional.empty();
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        List<FailureFeedback> matched = new ArrayList<>();
        for (FailureFeedback candidate : candidates) {
            String candidateLocator = candidate.getLocator();
            if (hasLocator && !dynamicLocator) {
                if (candidateLocator != null && locator.equalsIgnoreCase(candidateLocator)) {
                    matched.add(candidate);
                }
            } else if (hasLocator) {
                boolean locatorPatternMatch = candidateLocator != null
                        && normalizedRequestLocator.equalsIgnoreCase(normalizeLocatorForMatch(candidateLocator));
                boolean stepMatch = normalizedStep != null && !normalizedStep.isBlank()
                        && normalizedStep.equalsIgnoreCase(candidate.getNormalizedStep());
                if (locatorPatternMatch || stepMatch) {
                    matched.add(candidate);
                }
            } else if (normalizedStep != null && !normalizedStep.isBlank()
                    && normalizedStep.equalsIgnoreCase(candidate.getNormalizedStep())) {
                matched.add(candidate);
            }
        }

        if (matched.isEmpty()) {
            return Optional.empty();
        }

        Set<String> distinctHumanClassifications = new LinkedHashSet<>();
        for (FailureFeedback candidate : matched) {
            distinctHumanClassifications.add(candidate.getHumanClassification().trim().toUpperCase());
        }
        if (distinctHumanClassifications.size() > 1) {
            log.info(
                    "Historical similarity candidates for exceptionType={} disagree on classification {}; "
                    + "not reusing, falling through to Ollama.",
                    exceptionType, distinctHumanClassifications);
            return Optional.empty();
        }

        return Optional.of(toResponse(matched.get(0)));
    }

    private FailureClassificationResponse toResponse(FailureFeedback feedback) {
        String ai = feedback.getAiClassification();
        String human = feedback.getHumanClassification();
        boolean hasHuman = human != null && !human.isBlank();
        String effective = hasHuman ? human : ai;
        String source = hasHuman ? SOURCE_HUMAN_CORRECTED : SOURCE_AI;
        return new FailureClassificationResponse(feedback.getFailureId(), ai, human, effective, source);
    }

    // ------------------------------------------------------------------------------------------
    // Small, self-contained normalization helpers (intentionally NOT shared with
    // ReportInvestigationService's pre-analysis clustering, per task scope).
    // ------------------------------------------------------------------------------------------

    private String normalizeStep(String step) {
        if (step == null) {
            return null;
        }
        String withoutKeyword = GHERKIN_KEYWORD.matcher(step.trim()).replaceFirst("");
        return withoutKeyword.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private String normalizeError(String error) {
        if (error == null) {
            return null;
        }
        String firstLine = error.split("\\r?\\n", 2)[0];
        String normalized = firstLine.trim().toLowerCase().replaceAll("\\s+", " ");
        return normalized.length() > 500 ? normalized.substring(0, 500) : normalized;
    }

    private String extractExceptionType(String combined) {
        if (combined == null) {
            return null;
        }
        Matcher matcher = EXCEPTION_CLASS.matcher(combined);
        if (matcher.find()) {
            String fullyQualified = matcher.group(1);
            int lastDot = fullyQualified.lastIndexOf('.');
            return lastDot >= 0 ? fullyQualified.substring(lastDot + 1) : fullyQualified;
        }
        return null;
    }

    private String extractLocator(String combined) {
        if (combined == null) {
            return null;
        }
        for (Pattern pattern : LOCATOR_PATTERNS) {
            Matcher matcher = pattern.matcher(combined);
            if (matcher.find()) {
                String value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group(0);
                return value == null ? null : value.trim();
            }
        }
        return null;
    }

    private String extractStackTracePattern(String stackTrace) {
        if (stackTrace == null || stackTrace.isBlank()) {
            return null;
        }
        for (String line : stackTrace.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed.length() > 300 ? trimmed.substring(0, 300) : trimmed;
            }
        }
        return null;
    }

    private boolean isLikelyDynamicLocator(String locator) {
        if (locator == null || locator.isBlank()) {
            return false;
        }
        String lower = locator.toLowerCase();
        return lower.matches(".*[_-]\\d{3,}.*")
                || lower.matches(".*\\[\\d+\\].*")
                || lower.matches(".*\\d{4,}.*");
    }

    private String normalizeLocatorForMatch(String locator) {
        if (locator == null) {
            return "";
        }
        return locator.trim().toLowerCase()
                .replaceAll("\\[\\d+\\]", "[#]")
                .replaceAll("\\d{3,}", "<num>")
                .replaceAll("\\s+", " ");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * A persisted record is complete when all rich-analysis columns are present (null indicates
     * legacy non-rich persistence). Empty strings or [] are treated as legitimate AI outputs.
     */
    public boolean isAnalysisComplete(FailureFeedback feedback) {
        if (feedback == null) {
            return false;
        }
        return feedback.getRootCauseType() != null
                && isRootCauseMeaningful(feedback.getRootCause())
                && feedback.getSeverity() != null
                && feedback.getRecommendedAction() != null
                && feedback.getSuggestedFix() != null
                && feedback.getSimilarPatterns() != null
                && feedback.getScreenshotObservation() != null
                && feedback.getEvidenceJson() != null
                && feedback.getMissingEvidenceJson() != null
                && feedback.getPreventionTipsJson() != null
                && feedback.getStepsToReproduceJson() != null
                && feedback.getSource() != null;
    }

    private boolean isRootCauseMeaningful(String rootCause) {
        if (rootCause == null || rootCause.isBlank()) {
            return false;
        }
        String normalized = rootCause.trim().toLowerCase();
        if (normalized.startsWith("result loaded from previously saved failure details")) {
            return false;
        }
        if (normalized.contains("there may be a delay in page loading")
                || normalized.contains("there may be a network issue")
                || normalized.contains("the element was not visible")) {
            return false;
        }
        if (normalized.startsWith("timeoutexception")
                && (normalized.contains("delay in page loading") || normalized.contains("element visibility"))) {
            return false;
        }
        return true;
    }

    private String toJsonArray(List<String> values) {
        if (values == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize list field for failure feedback persistence: {}", e.getMessage());
            return null;
        }
    }

    public static String calculateScreenshotHash(String failureImage) {
        if (failureImage == null || failureImage.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(failureImage.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

