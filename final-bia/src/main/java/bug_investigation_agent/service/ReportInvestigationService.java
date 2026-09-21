package bug_investigation_agent.service;

import bug_investigation_agent.mapper.InvestigationRequestMapper;
import bug_investigation_agent.model.request.InvestigationRequest;
import bug_investigation_agent.model.request.ReportFailure;
import bug_investigation_agent.model.request.ReportInvestigationRequest;
import bug_investigation_agent.model.response.FailureClusterResponse;
import bug_investigation_agent.model.response.FailureInvestigation;
import bug_investigation_agent.model.response.InvestigationResponse;
import bug_investigation_agent.model.response.ReportAnalysisSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReportInvestigationService {
    private static final Logger log = LoggerFactory.getLogger(ReportInvestigationService.class);

    private final InvestigationService investigationService;
    private final InvestigationRequestMapper requestMapper;
    private final FailureClusteringService failureClusteringService;

    /**
     * Minimum weighted similarity score (0.0-1.0) required for a new failure to be merged into an
     * existing pre-analysis group instead of starting a new one. Configurable via
     * {@code failure.clustering.pre-analysis.threshold} in application.properties.
     */
    @Value("${failure.clustering.pre-analysis.threshold:0.80}")
    private double preAnalysisThreshold;

    public ReportInvestigationService(
            InvestigationService investigationService,
            InvestigationRequestMapper requestMapper,
            FailureClusteringService failureClusteringService) {

        this.investigationService = investigationService;
        this.requestMapper = requestMapper;
        this.failureClusteringService = failureClusteringService;
    }

    public ReportAnalysisResult analyzeReport(
            ReportInvestigationRequest request) {

        long analysisStart = System.nanoTime();

        List<ReportFailure> failures =
                request == null
                        ? new ArrayList<>()
                        : request.getFailures();

        if (failures == null) {
            failures = new ArrayList<>();
        }

        /*
         * Pre-cluster failures BEFORE calling the AI: many failures across test cases share the
         * same underlying failed step/exception/locator/error pattern (e.g. the same locator
         * timing out in 5 different scenarios). Calling the (slow) Ollama model once per
         * near-identical failure wastes a lot of time. Instead, group failures using a
         * deterministic weighted-similarity comparison of four components (step, exception,
         * locator, error body), call the AI only once per group (using the most
         * information-rich member as the representative - preferring one with a screenshot
         * attached), then apply that single investigation result to every failure in the group.
         */
        List<FailureComponents> components = new ArrayList<>(failures.size());
        for (ReportFailure failure : failures) {
            components.add(extractComponents(failure));
        }

        // Also compute the OLD exact-signature grouping purely for metrics/comparison purposes.
        int oldExactGroups = countOldExactSignatureGroups(failures);

        Map<Integer, List<Integer>> groupsByRepresentative = groupFailuresBySimilarity(components);

        InvestigationResponse[] investigationByIndex = new InvestigationResponse[failures.size()];

        // Report-level metrics accumulators (measurement only - no behavior/logic change).
        int ollamaCalls = 0;
        int retries = 0;
        int textCalls = 0;
        int visionCalls = 0;
        long totalOllamaTimeMillis = 0;

        for (List<Integer> indices : groupsByRepresentative.values()) {
            int representativeIndex = pickRepresentative(failures, indices);
            ReportFailure representative = failures.get(representativeIndex);

            InvestigationRequest investigationRequest = requestMapper.map(representative);
            String scenarioId = representative.getScenarioName() != null && !representative.getScenarioName().isBlank()
                    ? representative.getScenarioName()
                    : representative.getTestCaseID();

            InvestigationService.InvestigationOutcome outcome =
                    investigationService.investigateWithMetrics(investigationRequest, scenarioId);
            InvestigationResponse investigation = outcome.response();

            ollamaCalls++;
            List<InvestigationService.OllamaCallMetrics> callMetrics = outcome.callMetrics();
            if (!callMetrics.isEmpty()) {
                boolean vision = callMetrics.get(0).vision();
                if (vision) {
                    visionCalls++;
                } else {
                    textCalls++;
                }
                retries += Math.max(0, callMetrics.size() - 1);
                for (InvestigationService.OllamaCallMetrics metrics : callMetrics) {
                    totalOllamaTimeMillis += metrics.totalMillis();
                }
            }

            for (int index : indices) {
                investigationByIndex[index] = investigation;
            }
        }

        /*
         * Build the final investigations list in the original failure order, reusing the shared
         * investigation result for failures that were clustered together.
         */
        List<FailureInvestigation> investigations = new ArrayList<>();
        for (int i = 0; i < failures.size(); i++) {
            ReportFailure failure = failures.get(i);

            FailureInvestigation failureInvestigation =
                    new FailureInvestigation(
                            failure.getTestCaseID(),
                            failure.getScenarioName(),
                            failure.getFeatureName(),
                            failure.getFailedStepLine(),
                            failure.getErrorMessage(),
                            failure.getFailureImage(),
                            failure.getVideoUrl(),
                            failure.getStackTrace(),
                            failure.getConsoleLogs(),
                            investigationByIndex[i]
                    );

            investigations.add(failureInvestigation);
        }

        /*
         * Cluster the investigated failures.
         */
        FailureClusterResponse clustering =
                failureClusteringService.clusterFailures(
                        investigations
                );

        /*
         * Build summary statistics.
         */
        ReportAnalysisSummary summary =
                buildSummary(investigations);

        long totalAnalysisMillis = (System.nanoTime() - analysisStart) / 1_000_000;
        logReportMetrics(
                failures.size(), oldExactGroups, groupsByRepresentative.size(), ollamaCalls, retries,
                textCalls, visionCalls, totalOllamaTimeMillis, totalAnalysisMillis);

        return new ReportAnalysisResult(
                investigations,
                clustering,
                summary
        );
    }

    private void logReportMetrics(
            int totalFailures, int oldExactGroups, int newGroups, int ollamaCalls, int retries,
            int textCalls, int visionCalls, long totalOllamaTimeMillis, long totalAnalysisMillis) {
        int callsAvoided = totalFailures - ollamaCalls;
        log.info(
                "\n## REPORT ANALYSIS METRICS\n" +
                "Total failures: {}\n" +
                "Old groups (exact-match): {}\n" +
                "New groups (weighted-similarity): {}\n" +
                "Ollama calls: {}\n" +
                "Calls avoided: {}\n" +
                "Retries: {}\n" +
                "Text calls: {}\n" +
                "Vision calls: {}\n" +
                "Total Ollama time: {} ms\n" +
                "Total analysis time: {} ms\n",
                totalFailures, oldExactGroups, newGroups, ollamaCalls, callsAvoided, retries,
                textCalls, visionCalls, totalOllamaTimeMillis, totalAnalysisMillis);
    }

    /**
     * Picks which member of a signature group should be sent to the AI. Prefers a failure that
     * has a screenshot attached (richer evidence => better analysis for the whole group); falls
     * back to the first failure in the group otherwise.
     */
    private int pickRepresentative(List<ReportFailure> failures, List<Integer> indices) {
        for (int index : indices) {
            String image = failures.get(index).getFailureImage();
            if (image != null && !image.isBlank()) {
                return index;
            }
        }
        return indices.get(0);
    }

    // ------------------------------------------------------------------------------------------
    // Weighted-similarity pre-analysis grouping
    // ------------------------------------------------------------------------------------------

    private static final Pattern QUOTED_TEXT = Pattern.compile("['\"][^'\"]*['\"]");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final Pattern GHERKIN_KEYWORD =
            Pattern.compile("^(given|when|then|and|but)\\s+", Pattern.CASE_INSENSITIVE);

    /**
     * Recognized exception class name pattern, e.g. "org.openqa.selenium.TimeoutException" or
     * "java.lang.AssertionError". Captures the simple (last-segment) class name.
     */
    private static final Pattern EXCEPTION_CLASS =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_.$]*(?:Exception|Error))\\b");

    /**
     * Locator patterns recognized in error messages / stack traces. Each pattern's FIRST capture
     * group (if any) or full match is used as the locator's stable identity. These are checked in
     * order; the first pattern that matches wins.
     */
    private static final List<Pattern> LOCATOR_PATTERNS = List.of(
            Pattern.compile("resource-id\\s*[=:]\\s*['\"]?([\\w.:/-]+)['\"]?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("By\\.xpath\\s*:?\\s*(\"[^\"]+\"|'[^']+'|[^,)\\n]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("By\\.id\\s*:?\\s*(\"[^\"]+\"|'[^']+'|[^,)\\n]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("By\\.cssSelector\\s*:?\\s*(\"[^\"]+\"|'[^']+'|[^,)\\n]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("accessibilityId\\s*[=:]\\s*['\"]?([\\w.:/-]+)['\"]?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:test-id|data-testid)\\s*[=:]\\s*['\"]?([\\w.:/-]+)['\"]?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("xpath\\s*[=:]\\s*['\"]?([^'\"\\n]+)['\"]?", Pattern.CASE_INSENSITIVE)
    );

    /** Weights for the weighted similarity score. Must sum to 1.0. */
    private static final double WEIGHT_STEP = 0.35;
    private static final double WEIGHT_EXCEPTION = 0.25;
    private static final double WEIGHT_LOCATOR = 0.25;
    private static final double WEIGHT_ERROR_BODY = 0.15;

    /**
     * The extracted, normalized components used to compare two failures for pre-analysis
     * grouping. Each component is compared with deterministic exact-match equality (no
     * fuzzy/edit-distance matching).
     */
    private record FailureComponents(
            String step,
            String exception,
            String locator,
            String errorBody,
            String stackFrame,
            boolean hasLocator,
            boolean hasError
    ) {
    }

    /**
     * Extracts the STEP, EXCEPTION, LOCATOR, ERROR BODY and STACK FRAME components from a single
     * failure. See class-level grouping algorithm documentation for details on each component.
     */
    private FailureComponents extractComponents(ReportFailure failure) {
        if (failure == null) {
            return new FailureComponents("", "unknown", "", "no-error", "", false, false);
        }

        String step = normalizeStep(failure.getFailedStepLine());

        String errorMessage = failure.getErrorMessage();
        String stackTrace = failure.getStackTrace();
        String combinedForExtraction =
                (errorMessage == null ? "" : errorMessage) + "\n" + (stackTrace == null ? "" : stackTrace);

        String exception = extractExceptionClass(combinedForExtraction);
        String locator = extractLocator(combinedForExtraction);
        boolean hasLocator = !locator.isBlank();

        boolean hasError = errorMessage != null && !errorMessage.isBlank();
        String errorBody = normalizeErrorBody(errorMessage, exception, locator);

        String stackFrame = extractTopStackFrame(stackTrace);

        return new FailureComponents(step, exception, locator, errorBody, stackFrame, hasLocator, hasError);
    }

    /**
     * STEP COMPONENT: strips the leading Gherkin keyword (Given/When/Then/And/But), normalizes
     * whitespace/case, but preserves quoted values (they can distinguish different UI targets,
     * e.g. "Targets" vs "Rewards" navigation button) and digits (dynamic numeric IDs are handled
     * separately - here we keep them as-is since the step text alone is a coarse signal; the
     * locator/error components carry the more precise identity information).
     */
    private String normalizeStep(String step) {
        if (step == null || step.isBlank()) {
            return "";
        }
        String noKeyword = GHERKIN_KEYWORD.matcher(step.trim()).replaceFirst("");
        String collapsed = WHITESPACE.matcher(noKeyword).replaceAll(" ").trim();
        return collapsed.toLowerCase();
    }

    /**
     * EXCEPTION COMPONENT: extracts the (simple) exception class name from the error message or
     * stack trace, e.g. "TimeoutException", "AssertionError", "NullPointerException". Returns
     * "UNKNOWN" when no exception type can be identified.
     */
    private String extractExceptionClass(String text) {
        if (text == null || text.isBlank()) {
            return "UNKNOWN";
        }
        Matcher matcher = EXCEPTION_CLASS.matcher(text);
        if (matcher.find()) {
            String fullyQualified = matcher.group(1);
            int lastDot = fullyQualified.lastIndexOf('.');
            return lastDot >= 0 ? fullyQualified.substring(lastDot + 1) : fullyQualified;
        }
        return "UNKNOWN";
    }

    /**
     * LOCATOR COMPONENT: extracts a stable locator identity (resource-id, By.xpath, By.id,
     * By.cssSelector, accessibilityId, test-id/data-testid, or a generic xpath=... form) WITHOUT
     * applying digit-masking, so that structurally different locators such as
     * "opportunityCard_click_104712" and "opportunityCard_click_147653" remain distinct. Returns
     * an empty string when no locator can be identified.
     */
    private String extractLocator(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        for (Pattern pattern : LOCATOR_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                String value = matcher.groupCount() >= 1 && matcher.group(1) != null
                        ? matcher.group(1)
                        : matcher.group();
                // Trim surrounding quotes only - do NOT mask digits, they are part of the
                // locator's identity.
                String trimmed = value.trim();
                if (trimmed.length() >= 2
                        && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                                || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
                    trimmed = trimmed.substring(1, trimmed.length() - 1);
                }
                trimmed = WHITESPACE.matcher(trimmed).replaceAll(" ").trim();
                if (!trimmed.isBlank()) {
                    return trimmed;
                }
            }
        }
        return "";
    }

    /**
     * ERROR BODY COMPONENT: normalizes the remaining error message AFTER exception/locator
     * extraction. Uses the first line only (stack frames below are noisy). Quoted values are
     * masked ONLY when they don't look like a locator we already extracted separately (locator
     * identity is preserved in its own component, so masking it here is safe and avoids
     * double-counting). Digits are masked here (in the error body only, NOT in the locator
     * component) since they are typically dynamic values (counts, IDs) unrelated to locator
     * identity.
     */
    private String normalizeErrorBody(String errorMessage, String exception, String locator) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "no-error";
        }
        String firstLine = errorMessage.split("\\r?\\n", 2)[0];
        String withoutLocator = locator.isBlank() ? firstLine : firstLine.replace(locator, "*");
        String noQuotes = QUOTED_TEXT.matcher(withoutLocator).replaceAll("\"*\"");
        String noDigits = DIGITS.matcher(noQuotes).replaceAll("#");
        String collapsed = WHITESPACE.matcher(noDigits).replaceAll(" ").trim();
        return collapsed.toLowerCase();
    }

    /**
     * STACK TRACE COMPONENT (tie-breaker only, never sent to Ollama for clustering): returns a
     * small stable representation using the first application/test frame (a frame that does not
     * belong to well-known framework/library packages), falling back to the very first frame.
     */
    private String extractTopStackFrame(String stackTrace) {
        if (stackTrace == null || stackTrace.isBlank()) {
            return "";
        }
        String[] lines = stackTrace.split("\\r?\\n");
        String firstFrame = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("at ")) {
                continue;
            }
            if (firstFrame == null) {
                firstFrame = trimmed;
            }
            boolean isFramework =
                    trimmed.contains("org.openqa.selenium.support.ui.FluentWait")
                            || trimmed.contains("org.openqa.selenium.support.ui.WebDriverWait")
                            || trimmed.contains("org.testng.Assert")
                            || trimmed.contains("java.base/")
                            || trimmed.contains("io.appium.")
                            || trimmed.contains("io.cucumber.");
            if (!isFramework) {
                return normalizeStackFrame(trimmed);
            }
        }
        return firstFrame == null ? "" : normalizeStackFrame(firstFrame);
    }

    private String normalizeStackFrame(String frame) {
        // Strip line numbers (e.g. "(HomeSteps.java:80)" -> "(HomeSteps.java)") so that the
        // stack frame is stable across otherwise-identical code paths with minor line shifts.
        String noLineNumbers = frame.replaceAll(":\\d+\\)", ")");
        return WHITESPACE.matcher(noLineNumbers).replaceAll(" ").trim().toLowerCase();
    }

    /**
     * Groups failures using a deterministic weighted-similarity comparison against existing group
     * representatives (the first failure that started each group). For every new failure, the
     * similarity score against every existing group representative is computed; the failure joins
     * the highest-scoring group if that score is >= {@link #preAnalysisThreshold}, otherwise it
     * starts a new group. This intentionally does NOT use fuzzy/edit-distance matching - all
     * component comparisons are exact-match after normalization, per the safety rules below:
     * <ul>
     *   <li>Never group solely because the exception type matches.</li>
     *   <li>Never group solely because the step text matches.</li>
     *   <li>A locator mismatch (two different, non-blank locators) strongly prevents grouping.</li>
     *   <li>If only one failure has a locator, that is treated as a mismatch (no assumption of
     *       equivalence).</li>
     *   <li>Missing error text lowers confidence and cannot fully match a populated error body.</li>
     * </ul>
     *
     * @return an ordered map from the representative failure's index (first failure of the group,
     *         in original list order) to the list of ALL failure indices in that group (in
     *         original order, including the representative itself).
     */
    private Map<Integer, List<Integer>> groupFailuresBySimilarity(List<FailureComponents> components) {
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        List<Integer> groupRepresentativeIndex = new ArrayList<>();

        for (int i = 0; i < components.size(); i++) {
            FailureComponents current = components.get(i);

            int bestGroupRepresentative = -1;
            double bestScore = -1.0;
            ScoreBreakdown bestBreakdown = null;

            for (int representativeIdx : groupRepresentativeIndex) {
                FailureComponents representative = components.get(representativeIdx);
                ScoreBreakdown breakdown = scoreComponents(representative, current);
                if (breakdown.score > bestScore) {
                    bestScore = breakdown.score;
                    bestGroupRepresentative = representativeIdx;
                    bestBreakdown = breakdown;
                }
            }

            if (bestGroupRepresentative >= 0 && bestScore >= preAnalysisThreshold) {
                groups.get(bestGroupRepresentative).add(i);
                logGroupingDecision(i, bestGroupRepresentative, bestBreakdown, true);
            } else {
                groups.put(i, new ArrayList<>(List.of(i)));
                groupRepresentativeIndex.add(i);
                if (bestBreakdown != null) {
                    logGroupingDecision(i, bestGroupRepresentative, bestBreakdown, false);
                } else {
                    log.debug("Group G{}: Failure {} has no existing groups yet - creating new group", i, i);
                }
            }
        }

        return groups;
    }

    private void logGroupingDecision(int failureIndex, int representativeIndex, ScoreBreakdown breakdown, boolean merged) {
        if (!log.isDebugEnabled()) {
            return;
        }
        if (merged) {
            log.debug(
                    "Group (rep={}): Failure {} matched representative Failure {}\n" +
                    "Score: {} >= threshold {}\n" +
                    "Step: {}\nException: {}\nLocator: {}\nError: {}",
                    representativeIndex, failureIndex, representativeIndex,
                    String.format("%.2f", breakdown.score), preAnalysisThreshold,
                    breakdown.stepMatch ? "MATCH" : "NO MATCH",
                    breakdown.exceptionMatch ? "MATCH" : "NO MATCH",
                    breakdown.locatorMatch ? "MATCH" : "NO MATCH",
                    breakdown.errorBodyMatch ? "MATCH" : "NO MATCH");
        } else {
            log.debug(
                    "Failure {}: best candidate score {} < threshold {}\nCreating new group",
                    failureIndex, String.format("%.2f", breakdown.score), preAnalysisThreshold);
        }
    }

    private record ScoreBreakdown(
            double score,
            boolean stepMatch,
            boolean exceptionMatch,
            boolean locatorMatch,
            boolean errorBodyMatch
    ) {
    }

    /**
     * Computes the weighted similarity score between two failures' extracted components,
     * enforcing the safety rules that prevent over-eager grouping:
     * <ol>
     *   <li>Locator mismatch (both non-blank, different values) forces the locator component
     *       score to 0 AND caps the overall score below the default threshold, since two
     *       different, clearly-identified UI elements should not be merged.</li>
     *   <li>One-sided locator presence (one failure has a locator, the other doesn't) is treated
     *       as a mismatch - never assumed equivalent.</li>
     *   <li>Missing error text on either side never counts as a match against populated error
     *       text.</li>
     * </ol>
     */
    private ScoreBreakdown scoreComponents(FailureComponents a, FailureComponents b) {
        boolean stepMatch = !a.step().isBlank() && a.step().equals(b.step());

        boolean exceptionMatch = a.exception().equals(b.exception()) && !"UNKNOWN".equals(a.exception());

        boolean locatorMatch;
        boolean bothHaveDifferentLocators = false;
        if (a.hasLocator() && b.hasLocator()) {
            locatorMatch = a.locator().equals(b.locator());
            bothHaveDifferentLocators = !locatorMatch;
        } else if (!a.hasLocator() && !b.hasLocator()) {
            // Neither failure has a recognizable locator - treat as a neutral (non-differentiating)
            // match so failures without locators can still be grouped by step/exception/error.
            locatorMatch = true;
        } else {
            // Exactly one side has a locator - never assume equivalence.
            locatorMatch = false;
        }

        boolean errorBodyMatch;
        if (a.hasError() && b.hasError()) {
            errorBodyMatch = a.errorBody().equals(b.errorBody());
        } else if (!a.hasError() && !b.hasError()) {
            // Both missing error text - neutral, don't let it drive the decision either way.
            errorBodyMatch = false;
        } else {
            // One side has error text, the other doesn't - never match.
            errorBodyMatch = false;
        }

        double score =
                (stepMatch ? WEIGHT_STEP : 0.0)
                        + (exceptionMatch ? WEIGHT_EXCEPTION : 0.0)
                        + (locatorMatch ? WEIGHT_LOCATOR : 0.0)
                        + (errorBodyMatch ? WEIGHT_ERROR_BODY : 0.0);

        // Safety rule: if both failures have clearly different, meaningful locators, strongly
        // prevent grouping regardless of the other components matching, by hard-capping the
        // score below any reasonable threshold.
        if (bothHaveDifferentLocators) {
            score = Math.min(score, WEIGHT_STEP + WEIGHT_EXCEPTION - 0.01);
        }

        // Optional stack-frame tie-breaker: only nudges the score slightly when everything else
        // is already ambiguous - never dominates (small bonus, capped so it cannot itself cross
        // the default 0.80 threshold on its own).
        if (!bothHaveDifferentLocators
                && !a.stackFrame().isBlank()
                && a.stackFrame().equals(b.stackFrame())) {
            score = Math.min(1.0, score + 0.03);
        }

        return new ScoreBreakdown(score, stepMatch, exceptionMatch, locatorMatch, errorBodyMatch);
    }

    /**
     * Computes how many groups the OLD exact-signature algorithm (normalized failedStepLine +
     * normalized first-line error, exact string match) would have produced, purely for
     * before/after metrics comparison. Does not affect the actual grouping used for Ollama calls.
     */
    private int countOldExactSignatureGroups(List<ReportFailure> failures) {
        Map<String, List<Integer>> groupsBySignature = new LinkedHashMap<>();
        for (int i = 0; i < failures.size(); i++) {
            String signature = buildOldFailureSignature(failures.get(i));
            groupsBySignature.computeIfAbsent(signature, k -> new ArrayList<>()).add(i);
        }
        return groupsBySignature.size();
    }

    private String buildOldFailureSignature(ReportFailure failure) {
        if (failure == null) {
            return "unknown";
        }
        String step = oldNormalize(failure.getFailedStepLine());
        String error = oldNormalizeError(failure.getErrorMessage());
        return step + " || " + error;
    }

    private String oldNormalizeError(String error) {
        if (error == null || error.isBlank()) {
            return "no-error";
        }
        String firstLine = error.split("\\r?\\n", 2)[0];
        return oldNormalize(firstLine);
    }

    private String oldNormalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String noQuotes = QUOTED_TEXT.matcher(text).replaceAll("\"*\"");
        String noDigits = DIGITS.matcher(noQuotes).replaceAll("#");
        String collapsed = WHITESPACE.matcher(noDigits).replaceAll(" ").trim();
        return collapsed.toLowerCase();
    }

    private ReportAnalysisSummary buildSummary(
            List<FailureInvestigation> investigations) {

        int automationIssues = 0;
        int applicationIssues = 0;
        int apiIssues = 0;
        int dataIssues = 0;
        int environmentIssues = 0;
        int networkIssues = 0;
        int unknownIssues = 0;

        int lowSeverity = 0;
        int mediumSeverity = 0;
        int highSeverity = 0;
        int criticalSeverity = 0;

        for (FailureInvestigation failure : investigations) {

            if (failure == null ||
                    failure.getInvestigation() == null) {
                continue;
            }

            InvestigationResponse investigation =
                    failure.getInvestigation();

            /*
             * Classification
             */
            String classification =
                    investigation.getClassification();

            if (classification != null) {

                switch (classification.toUpperCase()) {

                    case "AUTOMATION_ISSUE" ->
                            automationIssues++;

                    case "APPLICATION_ISSUE" ->
                            applicationIssues++;

                    case "API_ISSUE" ->
                            apiIssues++;

                    case "DATA_ISSUE" ->
                            dataIssues++;

                    case "ENVIRONMENT_ISSUE" ->
                            environmentIssues++;

                    case "NETWORK_ISSUE" ->
                            networkIssues++;

                    case "UNKNOWN" ->
                            unknownIssues++;

                    default ->
                            unknownIssues++;
                }
            } else {
                unknownIssues++;
            }

            /*
             * Severity
             */
            String severity =
                    investigation.getSeverity();

            if (severity != null) {

                switch (severity.toUpperCase()) {

                    case "LOW" ->
                            lowSeverity++;

                    case "MEDIUM" ->
                            mediumSeverity++;

                    case "HIGH" ->
                            highSeverity++;

                    case "CRITICAL" ->
                            criticalSeverity++;

                    default -> {
                        // Ignore unknown severity values
                    }
                }
            }
        }

        return new ReportAnalysisSummary(
                automationIssues,
                applicationIssues,
                apiIssues,
                dataIssues,
                environmentIssues,
                networkIssues,
                unknownIssues,
                lowSeverity,
                mediumSeverity,
                highSeverity,
                criticalSeverity
        );
    }

    public record ReportAnalysisResult(
            List<FailureInvestigation> investigations,
            FailureClusterResponse clustering,
            ReportAnalysisSummary summary
    ) {
    }
}