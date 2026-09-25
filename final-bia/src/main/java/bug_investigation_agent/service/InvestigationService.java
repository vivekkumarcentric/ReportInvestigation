package bug_investigation_agent.service;

import bug_investigation_agent.client.OllamaClient;
import bug_investigation_agent.model.FailureFeedback;
import bug_investigation_agent.model.request.InvestigationRequest;
import bug_investigation_agent.model.response.InvestigationResponse;
import bug_investigation_agent.prompt.InvestigationPromptBuilder;
import bug_investigation_agent.util.FailureIdGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class InvestigationService {
    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);

    private final OllamaClient ollamaClient;
    private final InvestigationPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private final FailureFeedbackService failureFeedbackService;

    public InvestigationService(OllamaClient ollamaClient,
                                InvestigationPromptBuilder promptBuilder,
                                ObjectMapper objectMapper,
                                FailureFeedbackService failureFeedbackService) {
        this.ollamaClient = ollamaClient;
        this.promptBuilder = promptBuilder;
        this.objectMapper = objectMapper;
        this.failureFeedbackService = failureFeedbackService;
    }

    public InvestigationResponse investigate(InvestigationRequest request) {
        return investigateWithMetrics(request, deriveScenarioId(request)).response();
    }

    /**
     * Identical behavior to {@link #investigate(InvestigationRequest)} (same prompt, same
     * OllamaClient call, same JSON repair/parsing/retry logic, same classification output) but
     * additionally measures and logs performance metrics for every Ollama request attempt
     * (including retries), and returns those metrics alongside the result so callers that process
     * many failures (e.g. report analysis) can aggregate report-level totals.
     *
    * <p>Before calling Ollama, this checks whether this exact failure already exists in the
    * feedback database (matched by the same stable {@code failureId} used elsewhere for
    * persistence). If present, the saved details are returned directly - with zero Ollama calls -
    * instead of re-invoking the model for the same failure.</p>
     *
     * <p>If no exact match exists, a secondary cross-report similarity check
     * ({@link FailureFeedbackService#findHistoricalMatch(InvestigationRequest)}) looks for the
     * SAME underlying logical failure re-appearing under a DIFFERENT {@code failureId} (e.g. in a
     * different report run) and reuses that human classification too, under the same conditions
     * (single, non-conflicting match only).</p>
     */
    public InvestigationOutcome investigateWithMetrics(InvestigationRequest request, String scenarioId) {
        boolean forceReanalysis = request != null && request.isForceReanalysisEnabled();
        String failureId = FailureIdGenerator.generate(
                request == null ? null : request.getScenario(),
                request == null ? null : request.getFeature(),
                request == null ? null : request.getFailedStep(),
                request == null ? null : request.getError());
        String legacyFailureId = FailureIdGenerator.generateLegacy(
                request == null ? null : request.getScenario(),
                request == null ? null : request.getFeature(),
                request == null ? null : request.getFailedStep(),
                request == null ? null : request.getError());

        boolean historicalFound = false;
        FailureFeedback historicalContextRecord = null;
        if (forceReanalysis) {
            log.info("Force re-analysis requested for failureId={}. Bypassing historical analysis.", failureId);
        } else {
            Optional<FailureFeedback> exactMatch = failureFeedbackService.getFeedbackByFailureId(failureId);
            if (exactMatch.isPresent()) {
                historicalFound = true;
                historicalContextRecord = exactMatch.get();
                if (shouldReuseHistoricalOutcome(request, exactMatch.get())) {
                    log.info("Complete historical analysis found for failureId={}. Returning persisted result.", failureId);
                    return buildHistoricalOutcomeFromFeedback(failureId, exactMatch.get());
                }
                log.info("Historical record for failureId={} is complete but stale for the current screenshot; running enrichment analysis.", failureId);
            }

            if (!historicalFound && !failureId.equals(legacyFailureId)) {
                Optional<FailureFeedback> legacyMatch = failureFeedbackService.getFeedbackByFailureId(legacyFailureId);
                if (legacyMatch.isPresent()) {
                    historicalFound = true;
                    historicalContextRecord = legacyMatch.get();
                    failureFeedbackService.copyFeedbackToFailureId(legacyMatch.get(), failureId);
                    Optional<FailureFeedback> aliased = failureFeedbackService.getFeedbackByFailureId(failureId);
                    if (aliased.isPresent()) {
                        historicalContextRecord = aliased.get();
                    }
                    if (aliased.isPresent() && shouldReuseHistoricalOutcome(request, aliased.get())) {
                        log.info("Complete historical analysis found for failureId={}. Returning persisted result.", failureId);
                        return buildHistoricalOutcomeFromFeedback(failureId, aliased.get());
                    }
                    log.info("Historical analysis incomplete for failureId={}. Running enrichment analysis.", failureId);
                }
            }

            if (!historicalFound) {
                log.info("No historical analysis found for failureId={}. Running fresh analysis.", failureId);
            }
        }

        long promptBuildStart = System.nanoTime();
        String prompt = promptBuilder.buildPrompt(request, buildHistoricalPromptContext(historicalContextRecord));
        long promptBuildMillis = (System.nanoTime() - promptBuildStart) / 1_000_000;

        String image = request == null ? null : request.getFailureImage();
        int promptChars = prompt == null ? 0 : prompt.length();

        List<OllamaCallMetrics> callMetrics = new ArrayList<>();
        Exception lastError = null;
        String lastRawAiResponse = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            boolean isRetry = attempt > 1;
            OllamaClient.OllamaGenerationResult generation;
            try {
                generation = ollamaClient.generateWithMetrics(prompt, image);
            } catch (Exception e) {
                OllamaCallMetrics failedCallMetrics = new OllamaCallMetrics(
                        scenarioId, null, image != null && !image.isBlank(), isRetry,
                        promptChars, promptBuildMillis, 0, 0, promptBuildMillis, null, null);
                logOllamaMetrics(failedCallMetrics);
                throw new RuntimeException(
                        "Ollama AI Service Unavailable: " + e.getMessage() +
                        " Please ensure Ollama is running and required models are loaded.", e);
            }

            lastRawAiResponse = generation.response();

            long parseStart = System.nanoTime();
            try {
                String json = normalizeResponse(repairJson(extractJson(generation.response())));
                InvestigationResponse parsed = objectMapper.readValue(json, InvestigationResponse.class);
                correctHallucinatedScreenshotText(parsed, image);
                log.info("Updating existing failure record: {}", failureId);
                applyHumanFeedback(failureId, request, parsed, historicalFound);
                log.info("Updated historical analysis for failureId={} with enriched AI result.", failureId);

                long parseMillis = (System.nanoTime() - parseStart) / 1_000_000;
                long totalMillis = promptBuildMillis + generation.httpDurationMillis() + parseMillis;
                OllamaCallMetrics metrics = new OllamaCallMetrics(
                        scenarioId, generation.model(), generation.vision(), isRetry,
                        promptChars, promptBuildMillis, generation.httpDurationMillis(), parseMillis,
                        totalMillis, generation.promptEvalCount(), generation.evalCount());
                callMetrics.add(metrics);
                logOllamaMetrics(metrics);

                return new InvestigationOutcome(parsed, callMetrics);
            } catch (Exception e) {
                long parseMillis = (System.nanoTime() - parseStart) / 1_000_000;
                long totalMillis = promptBuildMillis + generation.httpDurationMillis() + parseMillis;
                OllamaCallMetrics metrics = new OllamaCallMetrics(
                        scenarioId, generation.model(), generation.vision(), isRetry,
                        promptChars, promptBuildMillis, generation.httpDurationMillis(), parseMillis,
                        totalMillis, generation.promptEvalCount(), generation.evalCount());
                callMetrics.add(metrics);
                logOllamaMetrics(metrics);
                lastError = e;
                // retry once - Ollama occasionally truncates/garbles JSON on long prompts
            }
        }

            String exceptionType = lastError == null ? "UNKNOWN" : lastError.getClass().getName();
            String exceptionMessage = lastError == null ? "null" : String.valueOf(lastError.getMessage());
            if (lastRawAiResponse == null || lastRawAiResponse.isBlank()) {
                log.error("AI response parsing failed after retries. exceptionType={} exceptionMessage={} RAW_AI_RESPONSE=<EMPTY>",
                    exceptionType, exceptionMessage);
            } else {
                String raw = lastRawAiResponse;
                int length = raw.length();
                String first200 = raw.substring(0, Math.min(200, length));
                String last200 = raw.substring(Math.max(0, length - 200));
                String truncated = raw.substring(0, Math.min(3000, length));
                log.error(
                    "AI response parsing failed after retries. exceptionType={} exceptionMessage={} RAW_AI_RESPONSE_LENGTH={} RAW_AI_RESPONSE_FIRST_200={} RAW_AI_RESPONSE_LAST_200={} RAW_AI_RESPONSE_TRUNCATED={}{}",
                    exceptionType,
                    exceptionMessage,
                    length,
                    first200,
                    last200,
                    truncated,
                    length > 3000 ? "<TRUNCATED>" : "");
            }

        throw new RuntimeException(
                "Failed to parse AI investigation response. Ollama did not return a valid investigation response.",
                lastError);
    }

    /**
     * Builds an {@link InvestigationOutcome} from an exact database hit for {@code failureId},
     * with empty {@code callMetrics} since Ollama was not invoked.
     */
    private InvestigationOutcome buildHistoricalOutcomeFromFeedback(String failureId, FailureFeedback feedback) {
        InvestigationResponse response = new InvestigationResponse();
        response.setFailureId(failureId);

        String ai = feedback.getAiClassification();
        String human = feedback.getHumanClassification();
        boolean hasHuman = human != null && !human.isBlank();

        String effective = hasHuman
                ? human
                : (ai == null || ai.isBlank() ? "UNKNOWN" : ai);

        response.setAiClassification(ai);
        response.setHumanClassification(hasHuman ? human : null);
        response.setClassification(effective);
        // This branch is only reached when we short-circuit to a complete persisted result.
        // The analysis source for this execution path is always historical retrieval.
        response.setSource(FailureFeedbackService.SOURCE_HISTORICAL);

        if (feedback.getConfidence() != null) {
            response.setConfidence(feedback.getConfidence());
        }
        if (feedback.getRootCause() != null && !feedback.getRootCause().isBlank()) {
            response.setRootCause(feedback.getRootCause());
        } else {
            response.setRootCause("Result loaded from previously saved failure details. Ollama was not called.");
        }
        response.setRootCauseType(feedback.getRootCauseType());
        response.setSeverity(feedback.getSeverity());
        response.setRecommendedAction(feedback.getRecommendedAction());
        response.setSuggestedFix(feedback.getSuggestedFix());
        response.setSimilarPatterns(feedback.getSimilarPatterns());
        response.setScreenshotObservation(feedback.getScreenshotObservation());
        response.setEvidence(parseListJson(feedback.getEvidenceJson()));
        response.setMissingEvidence(parseListJson(feedback.getMissingEvidenceJson()));
        response.setPreventionTips(parseListJson(feedback.getPreventionTipsJson()));
        response.setStepsToReproduce(parseListJson(feedback.getStepsToReproduceJson()));

        return new InvestigationOutcome(response, new ArrayList<>());
    }

    private boolean shouldReuseHistoricalOutcome(InvestigationRequest request, FailureFeedback feedback) {
        if (feedback == null || !failureFeedbackService.isAnalysisComplete(feedback)) {
            return false;
        }

        String failureImage = request == null ? null : request.getFailureImage();
        if (failureImage == null || failureImage.isBlank()) {
            return true;
        }

        String historicalScreenshotHash = feedback.getScreenshotHash();
        if (historicalScreenshotHash == null || historicalScreenshotHash.isBlank()) {
            return false;
        }

        String currentScreenshotHash = FailureFeedbackService.calculateScreenshotHash(failureImage);
        return currentScreenshotHash != null && currentScreenshotHash.equalsIgnoreCase(historicalScreenshotHash);
    }

    private List<String> parseListJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            log.warn("Failed to parse persisted JSON list field: {}", e.getMessage());
            return null;
        }
    }


    private String deriveScenarioId(InvestigationRequest request) {
        if (request == null) {
            return "unknown";
        }
        if (request.getScenario() != null && !request.getScenario().isBlank()) {
            return request.getScenario();
        }
        if (request.getTestName() != null && !request.getTestName().isBlank()) {
            return request.getTestName();
        }
        return "unknown";
    }

    private String buildHistoricalPromptContext(FailureFeedback feedback) {
        if (feedback == null) {
            return "NOT PROVIDED";
        }
        StringBuilder context = new StringBuilder();
        appendIfPresent(context, "Previous Source", feedback.getSource());
        appendIfPresent(context, "Previous AI Classification", feedback.getAiClassification());
        appendIfPresent(context, "Previous Human Classification", feedback.getHumanClassification());
        appendIfPresent(context, "Previous Root Cause", feedback.getRootCause());
        appendIfPresent(context, "Previous Root Cause Type", feedback.getRootCauseType());
        appendIfPresent(context, "Previous Similar Patterns", feedback.getSimilarPatterns());
        appendIfPresent(context, "Previous Screenshot Observation", feedback.getScreenshotObservation());
        return context.isEmpty() ? "NOT PROVIDED" : context.toString().trim();
    }

    private void appendIfPresent(StringBuilder context, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!context.isEmpty()) {
            context.append('\n');
        }
        context.append(label).append(": ").append(value.trim());
    }

    /**
     * Computes a stable {@code failureId} for this request, persists the AI's classification as
     * the baseline for that failure (never overwriting any existing human correction), and then
     * overlays any existing human correction onto the response so the caller/UI immediately sees
     * the correct effective classification/source - even the very first time this failure is
     * re-investigated after a human previously corrected it.
     *
     * <p>{@code failureId} is passed in (already computed once at the top of
     * {@link #investigateWithMetrics(InvestigationRequest, String)}) rather than recomputed here,
     * since the historical-lookup short-circuit needs the same id before Ollama is even called.</p>
     */
    private void applyHumanFeedback(String failureId,
                                    InvestigationRequest request,
                                    InvestigationResponse response,
                                    boolean historicalFound) {
        if (response == null) {
            return;
        }

        response.setFailureId(failureId);
        response.setAiClassification(response.getClassification());
        response.setHumanClassification(null);
        response.setSource(historicalFound
            ? FailureFeedbackService.SOURCE_AI_ENRICHED
            : FailureFeedbackService.SOURCE_AI);

        failureFeedbackService.recordAiResult(failureId, request, response);

        Optional<bug_investigation_agent.model.response.FailureClassificationResponse> existing =
                failureFeedbackService.getClassification(failureId);
        existing.ifPresent(feedback -> {
            if (feedback.getHumanClassification() != null && !feedback.getHumanClassification().isBlank()) {
                response.setHumanClassification(feedback.getHumanClassification());
                response.setClassification(feedback.getHumanClassification());
            }
        });
    }

    private void logOllamaMetrics(OllamaCallMetrics m) {
        log.info(
                "\n## OLLAMA METRICS\n" +
                "Scenario: {}\n" +
                "Model: {}\n" +
                "Vision: {}\n" +
                "Prompt chars: {}\n" +
                "Prompt build time: {} ms\n" +
                "HTTP duration: {} ms\n" +
                "Parse time: {} ms\n" +
                "Total duration: {} ms\n" +
                "Prompt tokens: {}\n" +
                "Output tokens: {}\n" +
                "Retry: {}\n",
                m.scenarioId(), m.model(), m.vision(), m.promptChars(), m.promptBuildMillis(),
                m.httpMillis(), m.parseMillis(), m.totalMillis(), m.promptTokens(), m.outputTokens(),
                m.retry());
    }

    /** Per-Ollama-request performance metrics (one entry per attempt, including retries). */
    public record OllamaCallMetrics(
            String scenarioId,
            String model,
            boolean vision,
            boolean retry,
            int promptChars,
            long promptBuildMillis,
            long httpMillis,
            long parseMillis,
            long totalMillis,
            Integer promptTokens,
            Integer outputTokens
    ) {
    }

    /** Result of {@link #investigateWithMetrics(InvestigationRequest, String)}: the parsed
     *  investigation response plus the metrics for every Ollama attempt made while producing it. */
    public record InvestigationOutcome(
            InvestigationResponse response,
            List<OllamaCallMetrics> callMetrics
    ) {
    }


    /**
     * The vision model occasionally ignores the attached image and hallucinates that no
     * screenshot was provided, even though one was sent. When we know for certain an image was
     * attached to this request, replace any such hallucinated text with an honest note instead of
     * showing a misleading "no screenshot" message in the UI.
     */
    private void correctHallucinatedScreenshotText(InvestigationResponse response, String image) {
        boolean imageWasSent = image != null && !image.isBlank();
        if (!imageWasSent || response == null) {
            return;
        }
        String observation = response.getScreenshotObservation();
        if (observation != null && observation.toLowerCase().contains("no screenshot")
                || (observation != null && observation.toLowerCase().contains("not provided"))) {
            response.setScreenshotObservation(
                    "A screenshot was attached but the AI vision model did not clearly describe it. "
                    + "Please review the screenshot manually alongside this analysis.");
        }
    }

    private String extractJson(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalArgumentException("Ollama returned an empty response");
        }
        String cleaned = response.trim()
                .replace("```json", "")
                .replace("```JSON", "")
                .replace("```", "")
                .trim();

        int start = cleaned.indexOf('{');
        if (start < 0) throw new IllegalArgumentException("No JSON object found in Ollama response");

        int end = cleaned.lastIndexOf('}');
        if (end > start) return cleaned.substring(start, end + 1);

        // No closing brace found at all (fully truncated) - return from first '{' to end,
        // repairJson() will attempt to close it.
        return cleaned.substring(start);
    }

    /**
     * Attempts to salvage a truncated/malformed JSON object returned by the LLM.
     * Scans the text tracking whether we're inside a string (respecting escapes) and the
     * current depth of open braces/brackets, then closes any dangling string and appends
     * the missing closing brackets so the result can be parsed, even if some trailing
     * content is lost. If the input already parses as-is, returns it unchanged.
     */
    private String repairJson(String json) {
        try {
            objectMapper.readTree(json);
            return json; // already valid
        } catch (Exception ignored) {
            // fall through to repair
        }

        StringBuilder result = new StringBuilder(json.length() + 16);
        java.util.Deque<Character> stack = new java.util.ArrayDeque<>();
        boolean inString = false;
        boolean escape = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            result.append(c);

            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (c == '{' || c == '[') {
                stack.push(c);
            } else if (c == '}' || c == ']') {
                if (!stack.isEmpty()) stack.pop();
            }
        }

        // If we ended mid-string, remove any trailing dangling partial token/comma and close the string.
        if (inString) {
            // Trim a trailing partial escape backslash if any.
            if (result.length() > 0 && result.charAt(result.length() - 1) == '\\') {
                result.setLength(result.length() - 1);
            }
            result.append('"');
        }

        // Drop a trailing dangling comma before we close remaining structures.
        int lastNonSpace = result.length() - 1;
        while (lastNonSpace >= 0 && Character.isWhitespace(result.charAt(lastNonSpace))) lastNonSpace--;
        if (lastNonSpace >= 0 && result.charAt(lastNonSpace) == ',') {
            result.setLength(lastNonSpace);
        }

        while (!stack.isEmpty()) {
            char open = stack.pop();
            result.append(open == '{' ? '}' : ']');
        }

        return result.toString();
    }

    private String normalizeResponse(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        if (!root.isObject()) throw new IllegalArgumentException("AI response is not a JSON object");

        ObjectNode object = (ObjectNode) root;
        normalizeString(object, "classification");
        normalizeString(object, "rootCauseType");
        normalizeString(object, "rootCause");
        normalizeString(object, "severity");
        normalizeString(object, "recommendedAction");
        normalizeString(object, "suggestedFix");
        normalizeString(object, "similarPatterns");
        normalizeString(object, "screenshotObservation");
        normalizeArray(object, "evidence");
        normalizeArray(object, "missingEvidence");
        normalizeArray(object, "stepsToReproduce");
        normalizeArray(object, "preventionTips");

        if (!object.has("confidence") || !object.get("confidence").canConvertToInt()) {
            object.put("confidence", 0);
        }
        return objectMapper.writeValueAsString(object);
    }

    private void normalizeString(ObjectNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || node.isNull()) {
            object.put(field, "");
        } else if (!node.isTextual()) {
            object.put(field, convertNodeToText(node));
        }
    }

    private void normalizeArray(ObjectNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || node.isNull()) {
            object.putArray(field);
            return;
        }
        if (!node.isArray()) {
            ArrayNode array = object.putArray(field);
            array.add(convertNodeToText(node));
            return;
        }
        ArrayNode array = (ArrayNode) node;
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isTextual()) {
                array.set(i, objectMapper.getNodeFactory().textNode(convertNodeToText(array.get(i))));
            }
        }
    }

    private String convertNodeToText(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        if (node.isArray()) {
            StringBuilder value = new StringBuilder();
            for (JsonNode item : node) {
                if (!value.isEmpty()) value.append("; ");
                value.append(convertNodeToText(item));
            }
            return value.toString();
        }
        if (node.isObject() && node.has("message")) {
            return node.get("message").asText();
        }
        return node.toString();
    }
}
