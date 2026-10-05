package bug_investigation_agent.service;

import bug_investigation_agent.client.OllamaClient;
import bug_investigation_agent.model.FailureFeedback;
import bug_investigation_agent.model.request.InvestigationRequest;
import bug_investigation_agent.prompt.InvestigationPromptBuilder;
import bug_investigation_agent.util.FailureIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests the historical-human-classification short-circuit in
 * {@link InvestigationService#investigateWithMetrics(InvestigationRequest, String)}: when a
 * failure has already been human-classified (looked up by the same stable {@code failureId} used
 * for feedback persistence), the historical classification must be returned directly with zero
 * Ollama calls - instead of re-invoking the AI.
 */
class InvestigationServiceTest {

    private OllamaClient ollamaClient;
    private InvestigationPromptBuilder promptBuilder;
    private FailureFeedbackService failureFeedbackService;
    private InvestigationService investigationService;

    @BeforeEach
    void setUp() {
        ollamaClient = mock(OllamaClient.class);
        promptBuilder = mock(InvestigationPromptBuilder.class);
        failureFeedbackService = mock(FailureFeedbackService.class);
        investigationService = new InvestigationService(
                ollamaClient, promptBuilder, new ObjectMapper(), failureFeedbackService);
    }

    private InvestigationRequest request() {
        InvestigationRequest request = new InvestigationRequest();
        request.setScenario("verify checkout flow");
        request.setFeature("Checkout");
        request.setFailedStep("Then user sees order confirmation");
        request.setError("java.lang.AssertionError: expected [true] but found [false]");
        return request;
    }

    @Test
    void historicalHumanClassification_shortCircuitsAndDoesNotCallOllama() {
        InvestigationRequest request = request();
        String failureId = FailureIdGenerator.generate(
                request.getScenario(), request.getFeature(), request.getFailedStep(), request.getError());

        FailureFeedback historical = new FailureFeedback();
        historical.setFailureId(failureId);
        historical.setAiClassification("AUTOMATION_ISSUE");
        historical.setHumanClassification("APPLICATION_ISSUE");
        historical.setRootCause("Known and confirmed by human");
        historical.setRootCauseType("CONFIRMED");
        historical.setConfidence(100);
        historical.setSeverity("HIGH");
        historical.setRecommendedAction("Escalate to app team");
        historical.setSuggestedFix("Fix route transition in checkout module");
        historical.setSimilarPatterns("Intermittent checkout navigation mismatch");
        historical.setScreenshotObservation("Checkout screen not rendered");
        historical.setEvidenceJson("[\"Step 4 failed\",\"AssertionError observed\"]");
        historical.setMissingEvidenceJson("[\"Network logs\"]");
        historical.setPreventionTipsJson("[\"Add page ready guard\"]");
        historical.setStepsToReproduceJson("[\"Login\",\"Go to checkout\"]");
        historical.setSource(FailureFeedbackService.SOURCE_HUMAN_CORRECTED);

        when(failureFeedbackService.getFeedbackByFailureId(failureId)).thenReturn(Optional.of(historical));
        when(failureFeedbackService.isAnalysisComplete(historical)).thenReturn(true);

        InvestigationService.InvestigationOutcome outcome =
                investigationService.investigateWithMetrics(request, "verify checkout flow");

        assertThat(outcome.response().getFailureId()).isEqualTo(failureId);
        assertThat(outcome.response().getAiClassification()).isEqualTo("AUTOMATION_ISSUE");
        assertThat(outcome.response().getHumanClassification()).isEqualTo("APPLICATION_ISSUE");
        assertThat(outcome.response().getClassification()).isEqualTo("APPLICATION_ISSUE");
        assertThat(outcome.response().getSource()).isEqualTo(FailureFeedbackService.SOURCE_HISTORICAL);
        assertThat(outcome.response().getRootCauseType()).isEqualTo("CONFIRMED");
        assertThat(outcome.response().getSeverity()).isEqualTo("HIGH");
        assertThat(outcome.response().getRecommendedAction()).isEqualTo("Escalate to app team");
        assertThat(outcome.response().getSuggestedFix()).isEqualTo("Fix route transition in checkout module");
        assertThat(outcome.response().getSimilarPatterns()).isEqualTo("Intermittent checkout navigation mismatch");
        assertThat(outcome.response().getScreenshotObservation()).isEqualTo("Checkout screen not rendered");
        assertThat(outcome.response().getEvidence()).containsExactly("Step 4 failed", "AssertionError observed");
        assertThat(outcome.response().getMissingEvidence()).containsExactly("Network logs");
        assertThat(outcome.response().getPreventionTips()).containsExactly("Add page ready guard");
        assertThat(outcome.response().getStepsToReproduce()).containsExactly("Login", "Go to checkout");
        assertThat(outcome.callMetrics()).isEmpty();

        // The whole point of the short-circuit: Ollama (and prompt building) must never be
        // invoked when a historical human classification already exists.
        verifyNoInteractions(ollamaClient);
        verifyNoInteractions(promptBuilder);
    }

    @Test
    void noHistoricalClassification_fallsThroughToOllama() {
        InvestigationRequest request = request();
        when(failureFeedbackService.getFeedbackByFailureId(anyString())).thenReturn(Optional.empty());
        when(promptBuilder.buildPrompt(any(InvestigationRequest.class), nullable(String.class))).thenReturn("prompt text");
                when(failureFeedbackService.getClassification(anyString())).thenReturn(Optional.empty());

        String json = "{"
                + "\"classification\":\"AUTOMATION_ISSUE\","
                + "\"rootCause\":\"Locator changed\","
                + "\"confidence\":80,"
                + "\"severity\":\"MEDIUM\","
                + "\"rootCauseType\":\"PROBABLE\""
                + "}";
        OllamaClient.OllamaGenerationResult generation =
                new OllamaClient.OllamaGenerationResult(json, "qwen2.5:7b", false, 10L, 100, 50);
        when(ollamaClient.generateWithMetrics(anyString(), any())).thenReturn(generation);

        InvestigationService.InvestigationOutcome outcome =
                investigationService.investigateWithMetrics(request, "verify checkout flow");

        assertThat(outcome.response().getClassification()).isEqualTo("AUTOMATION_ISSUE");
        assertThat(outcome.response().getSource()).isEqualTo(FailureFeedbackService.SOURCE_AI);
        assertThat(outcome.callMetrics()).hasSize(1);
    }

    @Test
    void malformedUnescapedInnerQuotesInAiJson_areRepairedAndParsed() {
        InvestigationRequest request = request();
        when(failureFeedbackService.getFeedbackByFailureId(anyString())).thenReturn(Optional.empty());
        when(promptBuilder.buildPrompt(any(InvestigationRequest.class), nullable(String.class))).thenReturn("prompt text");
        when(failureFeedbackService.getClassification(anyString())).thenReturn(Optional.empty());

        String malformedJson = "{" 
                + "\"classification\":\"AUTOMATION_ISSUE\"," 
                + "\"rootCauseType\":\"CONFIRMED\"," 
                + "\"rootCause\":\"The error message 'Then the heading should contain \"Everyday geer\"' suggests a locator mismatch.\"," 
                + "\"confidence\":90,"
                + "\"severity\":\"MEDIUM\"," 
                + "\"recommendedAction\":\"Update locator\"," 
                + "\"suggestedFix\":\"Fix selector\"," 
                + "\"stepsToReproduce\":[\"Open storefront\"],"
                + "\"evidence\":[\"Heading mismatch\"],"
                + "\"missingEvidence\":[\"Stack trace\"],"
                + "\"preventionTips\":[\"Keep locators updated\"],"
                + "\"similarPatterns\":\"Locator drift\""
                + "}";

        // Simulate model output with invalid JSON quote escaping in a string field.
        String malformedModelOutput = malformedJson.replace("\\\"Everyday geer\\\"", "\"Everyday geer\"");

        OllamaClient.OllamaGenerationResult generation =
                new OllamaClient.OllamaGenerationResult(malformedModelOutput, "qwen2.5:7b", false, 10L, 100, 50);
        when(ollamaClient.generateWithMetrics(anyString(), any())).thenReturn(generation);

        InvestigationService.InvestigationOutcome outcome =
                investigationService.investigateWithMetrics(request, "verify checkout flow");

        assertThat(outcome.response().getClassification()).isEqualTo("AUTOMATION_ISSUE");
        assertThat(outcome.response().getRootCause()).contains("Everyday geer");
        assertThat(outcome.response().getSource()).isEqualTo(FailureFeedbackService.SOURCE_AI);
        assertThat(outcome.callMetrics()).hasSize(1);
    }

    @Test
    void proseOnlyAiResponseWithoutJson_isConvertedToFallbackStructuredResponse() {
        InvestigationRequest request = request();
        when(failureFeedbackService.getFeedbackByFailureId(anyString())).thenReturn(Optional.empty());
        when(promptBuilder.buildPrompt(any(InvestigationRequest.class), nullable(String.class))).thenReturn("prompt text");
        when(failureFeedbackService.getClassification(anyString())).thenReturn(Optional.empty());

        String proseOnly = "Based on the provided failure evidence, the most probable root cause is AUTOMATION_ISSUE. "
                + "This appears to be a timing and locator stability problem rather than an application defect.";

        when(ollamaClient.generateWithMetrics(anyString(), any()))
                .thenReturn(new OllamaClient.OllamaGenerationResult(proseOnly, "qwen2.5:7b", false, 10L, 100, 50));

        InvestigationService.InvestigationOutcome outcome =
                investigationService.investigateWithMetrics(request, "verify checkout flow");

        assertThat(outcome.response().getClassification()).isEqualTo("AUTOMATION_ISSUE");
        assertThat(outcome.response().getRootCause()).contains("AUTOMATION_ISSUE");
        assertThat(outcome.response().getRootCauseType()).isEqualTo("POSSIBLE");
        assertThat(outcome.response().getConfidence()).isEqualTo(35);
        assertThat(outcome.response().getSource()).isEqualTo(FailureFeedbackService.SOURCE_AI);
        assertThat(outcome.callMetrics()).hasSize(1);
    }

        @Test
        void exactDbHitWithAiOnlyRecord_shortCircuitsAndDoesNotCallOllama() {
                InvestigationRequest request = request();
                String failureId = FailureIdGenerator.generate(
                                request.getScenario(), request.getFeature(), request.getFailedStep(), request.getError());

                FailureFeedback historical = new FailureFeedback();
                historical.setFailureId(failureId);
                historical.setAiClassification("AUTOMATION_ISSUE");
                historical.setHumanClassification(null);
                historical.setRootCause("Saved root cause from prior run");
                historical.setRootCauseType("PROBABLE");
                historical.setConfidence(81);
                historical.setSeverity("MEDIUM");
                historical.setRecommendedAction("Investigate automation step");
                historical.setSuggestedFix("Stabilize locator wait");
                historical.setSimilarPatterns("");
                historical.setScreenshotObservation("");
                historical.setEvidenceJson("[]");
                historical.setMissingEvidenceJson("[]");
                historical.setPreventionTipsJson("[]");
                historical.setStepsToReproduceJson("[]");
                historical.setSource(FailureFeedbackService.SOURCE_AI);

                when(failureFeedbackService.getFeedbackByFailureId(failureId)).thenReturn(Optional.of(historical));
                when(failureFeedbackService.isAnalysisComplete(historical)).thenReturn(true);

                InvestigationService.InvestigationOutcome outcome =
                                investigationService.investigateWithMetrics(request, "verify checkout flow");

                assertThat(outcome.response().getFailureId()).isEqualTo(failureId);
                assertThat(outcome.response().getAiClassification()).isEqualTo("AUTOMATION_ISSUE");
                assertThat(outcome.response().getHumanClassification()).isNull();
                assertThat(outcome.response().getClassification()).isEqualTo("AUTOMATION_ISSUE");
                assertThat(outcome.response().getSource()).isEqualTo(FailureFeedbackService.SOURCE_HISTORICAL);
                assertThat(outcome.response().getRootCauseType()).isEqualTo("PROBABLE");
                assertThat(outcome.callMetrics()).isEmpty();

                verifyNoInteractions(ollamaClient);
                verifyNoInteractions(promptBuilder);
        }

    @Test
    void aiOnlyHistoricalWithoutErrorStackOrScreenshot_triggersFreshAnalysis() {
        InvestigationRequest request = new InvestigationRequest();
        request.setScenario("SAVE10 should apply a ten percent discount");
        request.setFeature("Storefront shopping flow");
        request.setFailedStep("Then the discount for promo code \"SAVE10\" should equal 10 percent of the item price for SKU \"NS-101\"");
        request.setError("");
        request.setStackTrace("");
        request.setFailureImage("");

        String failureId = FailureIdGenerator.generate(
                request.getScenario(), request.getFeature(), request.getFailedStep(), request.getError());

        FailureFeedback historical = new FailureFeedback();
        historical.setFailureId(failureId);
        historical.setAiClassification("APPLICATION_ISSUE");
        historical.setHumanClassification(null);
        historical.setRootCause("Historical AI-only guess");
        historical.setRootCauseType("POSSIBLE");
        historical.setConfidence(60);
        historical.setSeverity("MEDIUM");
        historical.setRecommendedAction("Historical action");
        historical.setSuggestedFix("Historical fix");
        historical.setSource(FailureFeedbackService.SOURCE_AI);

        when(failureFeedbackService.getFeedbackByFailureId(failureId)).thenReturn(Optional.of(historical));
        when(failureFeedbackService.isAnalysisComplete(historical)).thenReturn(true);
        when(promptBuilder.buildPrompt(any(InvestigationRequest.class), nullable(String.class))).thenReturn("prompt text");
        when(failureFeedbackService.getClassification(anyString())).thenReturn(Optional.empty());
        when(ollamaClient.generateWithMetrics(anyString(), any())).thenReturn(
                new OllamaClient.OllamaGenerationResult(
                        "{\"classification\":\"AUTOMATION_ISSUE\",\"rootCause\":\"Fresh run\",\"confidence\":70,\"severity\":\"MEDIUM\",\"rootCauseType\":\"POSSIBLE\"}",
                        "qwen2.5:7b", false, 10L, 100, 50));

        InvestigationService.InvestigationOutcome outcome =
                investigationService.investigateWithMetrics(request, request.getScenario());

        assertThat(outcome.response().getClassification()).isEqualTo("AUTOMATION_ISSUE");
        assertThat(outcome.response().getSource()).isEqualTo(FailureFeedbackService.SOURCE_AI);
        assertThat(outcome.callMetrics()).hasSize(1);
    }

    @Test
    void currentScreenshotOverridesStaleHistoricalNoScreenshotObservation() {
        InvestigationRequest request = request();
        request.setFailureImage("data:image/png;base64,AAAA");
        String failureId = FailureIdGenerator.generate(
                request.getScenario(), request.getFeature(), request.getFailedStep(), request.getError());

        FailureFeedback historical = new FailureFeedback();
        historical.setFailureId(failureId);
        historical.setAiClassification("APPLICATION_ISSUE");
        historical.setHumanClassification(null);
        historical.setRootCause("Saved root cause from prior run");
        historical.setRootCauseType("PROBABLE");
        historical.setConfidence(81);
        historical.setSeverity("MEDIUM");
        historical.setRecommendedAction("Investigate automation step");
        historical.setSuggestedFix("Stabilize locator wait");
        historical.setSimilarPatterns("Element locator mismatch");
        historical.setScreenshotObservation("No screenshot provided - cannot visually confirm root cause");
        historical.setEvidenceJson("[\"Error details not available\"]");
        historical.setMissingEvidenceJson("[\"Specific UI state\"]");
        historical.setPreventionTipsJson("[\"Use stable locators\"]");
        historical.setStepsToReproduceJson("[\"Login\",\"Navigate\"]");
        historical.setSource(FailureFeedbackService.SOURCE_AI);

        when(failureFeedbackService.getFeedbackByFailureId(failureId)).thenReturn(Optional.of(historical));
        when(failureFeedbackService.isAnalysisComplete(historical)).thenReturn(true);
        when(promptBuilder.buildPrompt(any(InvestigationRequest.class), nullable(String.class))).thenReturn("prompt text");
        when(failureFeedbackService.getClassification(anyString())).thenReturn(Optional.empty());

        String json = "{"
                + "\"classification\":\"APPLICATION_ISSUE\","
                + "\"rootCause\":\"Fresh AI result from current screenshot\","
                + "\"confidence\":93,"
                + "\"severity\":\"HIGH\","
                + "\"rootCauseType\":\"CONFIRMED\""
                + "}";
        when(ollamaClient.generateWithMetrics(anyString(), any()))
                .thenReturn(new OllamaClient.OllamaGenerationResult(json, "qwen2.5:7b", false, 10L, 100, 50));

        InvestigationService.InvestigationOutcome outcome =
                investigationService.investigateWithMetrics(request, "verify checkout flow");

        assertThat(outcome.response().getFailureId()).isEqualTo(failureId);
        assertThat(outcome.response().getClassification()).isEqualTo("APPLICATION_ISSUE");
        assertThat(outcome.response().getSource()).isEqualTo(FailureFeedbackService.SOURCE_AI_ENRICHED);
        assertThat(outcome.response().getRootCause()).isEqualTo("Fresh AI result from current screenshot");
        assertThat(outcome.callMetrics()).hasSize(1);

        verify(failureFeedbackService).recordAiResult(anyString(), any(InvestigationRequest.class), any());
    }

    @Test
        void sameFailureIdAndSameScreenshot_withBlankHistoricalObservation_triggersFreshAnalysis() {
        InvestigationRequest request = request();
        request.setFailureImage("data:image/png;base64,AAAA");
        String failureId = FailureIdGenerator.generate(
                request.getScenario(), request.getFeature(), request.getFailedStep(), request.getError());

        FailureFeedback historical = new FailureFeedback();
        historical.setFailureId(failureId);
        historical.setAiClassification("AUTOMATION_ISSUE");
        historical.setHumanClassification(null);
        historical.setRootCause("Saved root cause from prior run");
        historical.setRootCauseType("PROBABLE");
        historical.setConfidence(81);
        historical.setSeverity("MEDIUM");
        historical.setRecommendedAction("Investigate automation step");
        historical.setSuggestedFix("Stabilize locator wait");
        historical.setSimilarPatterns("Element locator mismatch");
        historical.setScreenshotObservation("");
        historical.setScreenshotHash(FailureFeedbackService.calculateScreenshotHash(request.getFailureImage()));
        historical.setEvidenceJson("[\"Error details not available\"]");
        historical.setMissingEvidenceJson("[\"Specific UI state\"]");
        historical.setPreventionTipsJson("[\"Use stable locators\"]");
        historical.setStepsToReproduceJson("[\"Login\",\"Navigate\"]");
        historical.setSource(FailureFeedbackService.SOURCE_AI);

        when(failureFeedbackService.getFeedbackByFailureId(failureId)).thenReturn(Optional.of(historical));
        when(failureFeedbackService.isAnalysisComplete(historical)).thenReturn(true);
        when(promptBuilder.buildPrompt(any(InvestigationRequest.class), nullable(String.class))).thenReturn("prompt text");
        when(failureFeedbackService.getClassification(anyString())).thenReturn(Optional.empty());
        when(ollamaClient.generateWithMetrics(anyString(), any())).thenReturn(
                new OllamaClient.OllamaGenerationResult(
                        "{\"classification\":\"APPLICATION_ISSUE\",\"rootCause\":\"Fresh AI result\",\"confidence\":90,\"severity\":\"HIGH\",\"rootCauseType\":\"CONFIRMED\"}",
                        "qwen2.5:7b", false, 10L, 100, 50));

        InvestigationService.InvestigationOutcome outcome =
                investigationService.investigateWithMetrics(request, "verify checkout flow");

        assertThat(outcome.response().getSource()).isEqualTo(FailureFeedbackService.SOURCE_AI_ENRICHED);
        assertThat(outcome.callMetrics()).hasSize(1);
        verify(failureFeedbackService).recordAiResult(anyString(), any(InvestigationRequest.class), any());
    }

    @Test
    void sameFailureIdAndSameScreenshot_withHistoricalObservation_reusesHistoricalOutcome() {
        InvestigationRequest request = request();
        request.setFailureImage("data:image/png;base64,AAAA");
        String failureId = FailureIdGenerator.generate(
                request.getScenario(), request.getFeature(), request.getFailedStep(), request.getError());

        FailureFeedback historical = new FailureFeedback();
        historical.setFailureId(failureId);
        historical.setAiClassification("AUTOMATION_ISSUE");
        historical.setHumanClassification(null);
        historical.setRootCause("Saved root cause from prior run");
        historical.setRootCauseType("PROBABLE");
        historical.setConfidence(81);
        historical.setSeverity("MEDIUM");
        historical.setRecommendedAction("Investigate automation step");
        historical.setSuggestedFix("Stabilize locator wait");
        historical.setSimilarPatterns("Element locator mismatch");
        historical.setScreenshotObservation("Screenshot shows stale spinner on checkout page");
        historical.setScreenshotHash(FailureFeedbackService.calculateScreenshotHash(request.getFailureImage()));
        historical.setEvidenceJson("[\"Error details not available\"]");
        historical.setMissingEvidenceJson("[\"Specific UI state\"]");
        historical.setPreventionTipsJson("[\"Use stable locators\"]");
        historical.setStepsToReproduceJson("[\"Login\",\"Navigate\"]");
        historical.setSource(FailureFeedbackService.SOURCE_AI);

        when(failureFeedbackService.getFeedbackByFailureId(failureId)).thenReturn(Optional.of(historical));
        when(failureFeedbackService.isAnalysisComplete(historical)).thenReturn(true);

        InvestigationService.InvestigationOutcome outcome =
                investigationService.investigateWithMetrics(request, "verify checkout flow");

        assertThat(outcome.response().getFailureId()).isEqualTo(failureId);
        assertThat(outcome.response().getSource()).isEqualTo(FailureFeedbackService.SOURCE_HISTORICAL);
        assertThat(outcome.callMetrics()).isEmpty();
        verifyNoInteractions(ollamaClient);
        verifyNoInteractions(promptBuilder);
    }

    @Test
    void sameFailureIdAndDifferentScreenshot_triggersFreshAnalysis() {
        InvestigationRequest request = request();
        request.setFailureImage("data:image/png;base64,AAAA");
        String failureId = FailureIdGenerator.generate(
                request.getScenario(), request.getFeature(), request.getFailedStep(), request.getError());

        FailureFeedback historical = new FailureFeedback();
        historical.setFailureId(failureId);
        historical.setAiClassification("APPLICATION_ISSUE");
        historical.setRootCause("Saved root cause from prior run");
        historical.setRootCauseType("PROBABLE");
        historical.setConfidence(81);
        historical.setSeverity("MEDIUM");
        historical.setRecommendedAction("Investigate automation step");
        historical.setSuggestedFix("Stabilize locator wait");
        historical.setSimilarPatterns("Element locator mismatch");
        historical.setScreenshotObservation("Old screenshot state");
        historical.setScreenshotHash(FailureFeedbackService.calculateScreenshotHash("data:image/png;base64,BBBB"));
        historical.setEvidenceJson("[\"Error details not available\"]");
        historical.setMissingEvidenceJson("[\"Specific UI state\"]");
        historical.setPreventionTipsJson("[\"Use stable locators\"]");
        historical.setStepsToReproduceJson("[\"Login\",\"Navigate\"]");
        historical.setSource(FailureFeedbackService.SOURCE_AI);

        when(failureFeedbackService.getFeedbackByFailureId(failureId)).thenReturn(Optional.of(historical));
        when(failureFeedbackService.isAnalysisComplete(historical)).thenReturn(true);
        when(promptBuilder.buildPrompt(any(InvestigationRequest.class), nullable(String.class))).thenReturn("prompt text");
        when(failureFeedbackService.getClassification(anyString())).thenReturn(Optional.empty());

        String json = "{"
                + "\"classification\":\"APPLICATION_ISSUE\","
                + "\"rootCause\":\"Fresh AI result from current screenshot\","
                + "\"confidence\":93,"
                + "\"severity\":\"HIGH\","
                + "\"rootCauseType\":\"CONFIRMED\""
                + "}";
        when(ollamaClient.generateWithMetrics(anyString(), any()))
                .thenReturn(new OllamaClient.OllamaGenerationResult(json, "qwen2.5:7b", false, 10L, 100, 50));

        InvestigationService.InvestigationOutcome outcome =
                investigationService.investigateWithMetrics(request, "verify checkout flow");

        assertThat(outcome.response().getSource()).isEqualTo(FailureFeedbackService.SOURCE_AI_ENRICHED);
        assertThat(outcome.response().getRootCause()).isEqualTo("Fresh AI result from current screenshot");
        assertThat(outcome.callMetrics()).hasSize(1);
        verify(failureFeedbackService).recordAiResult(anyString(), any(InvestigationRequest.class), any());
    }

    @Test
    void sameFailureIdWithMissingHistoricalScreenshotHash_triggersFreshAnalysis() {
        InvestigationRequest request = request();
        request.setFailureImage("data:image/png;base64,AAAA");
        String failureId = FailureIdGenerator.generate(
                request.getScenario(), request.getFeature(), request.getFailedStep(), request.getError());

        FailureFeedback historical = new FailureFeedback();
        historical.setFailureId(failureId);
        historical.setAiClassification("APPLICATION_ISSUE");
        historical.setRootCause("Saved root cause from prior run");
        historical.setRootCauseType("PROBABLE");
        historical.setConfidence(81);
        historical.setSeverity("MEDIUM");
        historical.setRecommendedAction("Investigate automation step");
        historical.setSuggestedFix("Stabilize locator wait");
        historical.setSimilarPatterns("Element locator mismatch");
        historical.setScreenshotObservation("Old screenshot state");
        historical.setScreenshotHash(null);
        historical.setEvidenceJson("[\"Error details not available\"]");
        historical.setMissingEvidenceJson("[\"Specific UI state\"]");
        historical.setPreventionTipsJson("[\"Use stable locators\"]");
        historical.setStepsToReproduceJson("[\"Login\",\"Navigate\"]");
        historical.setSource(FailureFeedbackService.SOURCE_AI);

        when(failureFeedbackService.getFeedbackByFailureId(failureId)).thenReturn(Optional.of(historical));
        when(failureFeedbackService.isAnalysisComplete(historical)).thenReturn(true);
        when(promptBuilder.buildPrompt(any(InvestigationRequest.class), nullable(String.class))).thenReturn("prompt text");
        when(failureFeedbackService.getClassification(anyString())).thenReturn(Optional.empty());
        when(ollamaClient.generateWithMetrics(anyString(), any())).thenReturn(
                new OllamaClient.OllamaGenerationResult("{\"classification\":\"APPLICATION_ISSUE\",\"rootCause\":\"Fresh AI result\",\"confidence\":90,\"severity\":\"HIGH\",\"rootCauseType\":\"CONFIRMED\"}", "qwen2.5:7b", false, 10L, 100, 50));

        InvestigationService.InvestigationOutcome outcome =
                investigationService.investigateWithMetrics(request, "verify checkout flow");

        assertThat(outcome.response().getSource()).isEqualTo(FailureFeedbackService.SOURCE_AI_ENRICHED);
        assertThat(outcome.callMetrics()).hasSize(1);
    }

    @Test
    void sameFailureIdWithoutCurrentScreenshot_reusesHistoricalOutcome() {
        InvestigationRequest request = request();
        String failureId = FailureIdGenerator.generate(
                request.getScenario(), request.getFeature(), request.getFailedStep(), request.getError());

        FailureFeedback historical = new FailureFeedback();
        historical.setFailureId(failureId);
        historical.setAiClassification("APPLICATION_ISSUE");
        historical.setRootCause("Saved root cause from prior run");
        historical.setRootCauseType("PROBABLE");
        historical.setConfidence(81);
        historical.setSeverity("MEDIUM");
        historical.setRecommendedAction("Investigate automation step");
        historical.setSuggestedFix("Stabilize locator wait");
        historical.setSimilarPatterns("Element locator mismatch");
        historical.setScreenshotObservation("");
        historical.setScreenshotHash(null);
        historical.setEvidenceJson("[]");
        historical.setMissingEvidenceJson("[]");
        historical.setPreventionTipsJson("[]");
        historical.setStepsToReproduceJson("[]");
        historical.setSource(FailureFeedbackService.SOURCE_AI);

        when(failureFeedbackService.getFeedbackByFailureId(failureId)).thenReturn(Optional.of(historical));
        when(failureFeedbackService.isAnalysisComplete(historical)).thenReturn(true);

        InvestigationService.InvestigationOutcome outcome =
                investigationService.investigateWithMetrics(request, "verify checkout flow");

        assertThat(outcome.response().getSource()).isEqualTo(FailureFeedbackService.SOURCE_HISTORICAL);
        assertThat(outcome.callMetrics()).isEmpty();
        verifyNoInteractions(ollamaClient);
    }

    @Test
        void legacyFailureIdMatch_enrichesAndMigratesAlias() {
        InvestigationRequest request = new InvestigationRequest();
        request.setScenario("verify target flow");
        request.setFeature("Sales Target");
        request.setFailedStep("Then target card is visible");
        request.setError("org.openqa.selenium.TimeoutException: not visible for resource-id=opportunityCard_click_147653");

        String newFailureId = FailureIdGenerator.generate(
                request.getScenario(), request.getFeature(), request.getFailedStep(), request.getError());
        String legacyFailureId = FailureIdGenerator.generateLegacy(
                request.getScenario(), request.getFeature(), request.getFailedStep(), request.getError());
        assertThat(newFailureId).isNotEqualTo(legacyFailureId);

        FailureFeedback legacy = new FailureFeedback();
        legacy.setFailureId(legacyFailureId);
        legacy.setAiClassification("AUTOMATION_ISSUE");
        legacy.setRootCause("Known timeout pattern");
        legacy.setConfidence(77);

                final int[] newIdLookups = {0};
                when(failureFeedbackService.getFeedbackByFailureId(newFailureId)).thenAnswer(invocation -> {
                        newIdLookups[0]++;
                        return newIdLookups[0] == 1 ? Optional.empty() : Optional.of(legacy);
                });
        when(failureFeedbackService.getFeedbackByFailureId(legacyFailureId)).thenReturn(Optional.of(legacy));
        when(failureFeedbackService.isAnalysisComplete(legacy)).thenReturn(false);
        when(promptBuilder.buildPrompt(any(InvestigationRequest.class), nullable(String.class))).thenReturn("prompt text");
        when(failureFeedbackService.getClassification(anyString())).thenReturn(Optional.empty());
        String json = "{"
                + "\"classification\":\"AUTOMATION_ISSUE\","
                + "\"rootCause\":\"Known timeout pattern\","
                + "\"confidence\":77,"
                + "\"severity\":\"MEDIUM\","
                + "\"rootCauseType\":\"PROBABLE\""
                + "}";
        when(ollamaClient.generateWithMetrics(anyString(), any())).thenReturn(
                new OllamaClient.OllamaGenerationResult(json, "qwen2.5:7b", false, 10L, 100, 50));

        InvestigationService.InvestigationOutcome outcome =
                investigationService.investigateWithMetrics(request, "verify target flow");

        assertThat(outcome.response().getFailureId()).isEqualTo(newFailureId);
        assertThat(outcome.response().getClassification()).isEqualTo("AUTOMATION_ISSUE");
        assertThat(outcome.response().getSource()).isEqualTo(FailureFeedbackService.SOURCE_AI_ENRICHED);
        assertThat(outcome.callMetrics()).hasSize(1);

        verify(failureFeedbackService).copyFeedbackToFailureId(legacy, newFailureId);
        verify(failureFeedbackService).recordAiResult(anyString(), any(InvestigationRequest.class), any());
    }

        @Test
        void oldHistoricalRecordWithoutRichFields_returnsWithoutError() {
                InvestigationRequest request = request();
                String failureId = FailureIdGenerator.generate(
                                request.getScenario(), request.getFeature(), request.getFailedStep(), request.getError());

                FailureFeedback historical = new FailureFeedback();
                historical.setFailureId(failureId);
                historical.setAiClassification("AUTOMATION_ISSUE");
                historical.setHumanClassification(null);
                historical.setRootCause("Legacy root cause only");
                historical.setConfidence(72);
                // Simulates pre-migration row: all new fields are null.

                when(failureFeedbackService.getFeedbackByFailureId(failureId)).thenReturn(Optional.of(historical));
                when(failureFeedbackService.isAnalysisComplete(historical)).thenReturn(false);
                when(promptBuilder.buildPrompt(any(InvestigationRequest.class), nullable(String.class))).thenReturn("prompt text");
                when(failureFeedbackService.getClassification(anyString())).thenReturn(Optional.empty());
                String json = "{"
                        + "\"classification\":\"AUTOMATION_ISSUE\","
                        + "\"rootCause\":\"Legacy row enriched\","
                        + "\"confidence\":72,"
                        + "\"severity\":\"MEDIUM\","
                        + "\"rootCauseType\":\"PROBABLE\""
                        + "}";
                when(ollamaClient.generateWithMetrics(anyString(), any())).thenReturn(
                        new OllamaClient.OllamaGenerationResult(json, "qwen2.5:7b", false, 10L, 100, 50));

                InvestigationService.InvestigationOutcome outcome =
                                investigationService.investigateWithMetrics(request, "verify checkout flow");

                assertThat(outcome.response().getFailureId()).isEqualTo(failureId);
                assertThat(outcome.response().getClassification()).isEqualTo("AUTOMATION_ISSUE");
                assertThat(outcome.response().getRootCause()).isEqualTo("Legacy row enriched");
                assertThat(outcome.callMetrics()).hasSize(1);

                verify(failureFeedbackService).recordAiResult(anyString(), any(InvestigationRequest.class), any());
        }

            @Test
            void forceReanalysis_bypassesHistoricalLookup_andCallsOllama() {
                InvestigationRequest request = request();
                request.setForceReanalysis(true);

                when(promptBuilder.buildPrompt(any(InvestigationRequest.class), nullable(String.class))).thenReturn("prompt text");
                when(failureFeedbackService.getClassification(anyString())).thenReturn(Optional.empty());
                String json = "{"
                        + "\"classification\":\"AUTOMATION_ISSUE\"," 
                        + "\"rootCause\":\"Fresh AI result\"," 
                        + "\"confidence\":85," 
                        + "\"severity\":\"MEDIUM\"," 
                        + "\"rootCauseType\":\"PROBABLE\""
                        + "}";
                when(ollamaClient.generateWithMetrics(anyString(), any()))
                        .thenReturn(new OllamaClient.OllamaGenerationResult(json, "qwen2.5:7b", false, 10L, 100, 50));

                InvestigationService.InvestigationOutcome outcome =
                        investigationService.investigateWithMetrics(request, "verify checkout flow");

                assertThat(outcome.response().getClassification()).isEqualTo("AUTOMATION_ISSUE");
                assertThat(outcome.response().getRootCause()).isEqualTo("Fresh AI result");
                verify(failureFeedbackService, never()).getFeedbackByFailureId(anyString());
                verify(ollamaClient, atLeastOnce()).generateWithMetrics(anyString(), any());
                verify(failureFeedbackService).recordAiResult(anyString(), any(InvestigationRequest.class), any());
            }

            @Test
            void imageAttachedAndAiReturnsBlankScreenshotObservation_setsFallbackObservation() {
                InvestigationRequest request = request();
                request.setFailureImage("data:image/png;base64,AAAA");

                when(failureFeedbackService.getFeedbackByFailureId(anyString())).thenReturn(Optional.empty());
                when(promptBuilder.buildPrompt(any(InvestigationRequest.class), nullable(String.class))).thenReturn("prompt text");
                when(failureFeedbackService.getClassification(anyString())).thenReturn(Optional.empty());
                String json = "{"
                        + "\"classification\":\"AUTOMATION_ISSUE\"," 
                        + "\"rootCause\":\"Locator changed\"," 
                        + "\"confidence\":80,"
                        + "\"severity\":\"MEDIUM\"," 
                        + "\"rootCauseType\":\"PROBABLE\"," 
                        + "\"screenshotObservation\":\"\""
                        + "}";
                when(ollamaClient.generateWithMetrics(anyString(), any()))
                        .thenReturn(new OllamaClient.OllamaGenerationResult(json, "qwen2.5:7b", true, 10L, 100, 50));

                InvestigationService.InvestigationOutcome outcome =
                        investigationService.investigateWithMetrics(request, "verify checkout flow");

                assertThat(outcome.response().getScreenshotObservation()).isEqualTo(
                        "A screenshot was attached but the AI vision model did not clearly describe it. "
                                + "Please review the screenshot manually alongside this analysis.");
            }

            @Test
            void imageAttachedAndAiReturnsNonEmptyScreenshotObservation_preservesObservation() {
                InvestigationRequest request = request();
                request.setFailureImage("data:image/png;base64,AAAA");

                when(failureFeedbackService.getFeedbackByFailureId(anyString())).thenReturn(Optional.empty());
                when(promptBuilder.buildPrompt(any(InvestigationRequest.class), nullable(String.class))).thenReturn("prompt text");
                when(failureFeedbackService.getClassification(anyString())).thenReturn(Optional.empty());
                String json = "{"
                        + "\"classification\":\"APPLICATION_ISSUE\","
                        + "\"rootCause\":\"Modal overlay blocks rewards list\","
                        + "\"confidence\":88,"
                        + "\"severity\":\"MEDIUM\","
                        + "\"rootCauseType\":\"CONFIRMED\","
                        + "\"screenshotObservation\":\"Rewards page visible behind unexpected filter modal\""
                        + "}";
                when(ollamaClient.generateWithMetrics(anyString(), any()))
                        .thenReturn(new OllamaClient.OllamaGenerationResult(json, "qwen2.5:7b", true, 10L, 100, 50));

                InvestigationService.InvestigationOutcome outcome =
                        investigationService.investigateWithMetrics(request, "verify checkout flow");

                assertThat(outcome.response().getScreenshotObservation())
                        .isEqualTo("Rewards page visible behind unexpected filter modal");
            }

            @Test
            void imageAttachedAndTruncatedMalformedOutput_repairsAndKeepsFallbackBehavior() {
                InvestigationRequest request = request();
                request.setFailureImage("data:image/png;base64,AAAA");

                when(failureFeedbackService.getFeedbackByFailureId(anyString())).thenReturn(Optional.empty());
                when(promptBuilder.buildPrompt(any(InvestigationRequest.class), nullable(String.class))).thenReturn("prompt text");
                when(failureFeedbackService.getClassification(anyString())).thenReturn(Optional.empty());

                // Deliberately truncated JSON: screenshotObservation field is missing.
                String truncated = "{"
                        + "\"classification\":\"APPLICATION_ISSUE\","
                        + "\"rootCause\":\"Modal opened unexpectedly\","
                        + "\"confidence\":85,"
                        + "\"severity\":\"MEDIUM\","
                        + "\"rootCauseType\":\"CONFIRMED\","
                        + "\"evidence\":[\"Rewards Claimed\"]";
                when(ollamaClient.generateWithMetrics(anyString(), any()))
                        .thenReturn(new OllamaClient.OllamaGenerationResult(truncated, "qwen2.5:7b", true, 10L, 100, 50));

                InvestigationService.InvestigationOutcome outcome =
                        investigationService.investigateWithMetrics(request, "verify checkout flow");

                assertThat(outcome.response().getClassification()).isEqualTo("APPLICATION_ISSUE");
                assertThat(outcome.response().getRootCause()).isEqualTo("Modal opened unexpectedly");
                assertThat(outcome.response().getScreenshotObservation()).isEqualTo(
                        "A screenshot was attached but the AI vision model did not clearly describe it. "
                                + "Please review the screenshot manually alongside this analysis.");
            }

            @Test
            void forceReanalysis_thenNormalCall_returnsUpdatedHistoricalAnalysis() {
                InvestigationRequest forcedRequest = request();
                forcedRequest.setForceReanalysis(true);
                InvestigationRequest normalRequest = request();

                String failureId = FailureIdGenerator.generate(
                        forcedRequest.getScenario(), forcedRequest.getFeature(), forcedRequest.getFailedStep(), forcedRequest.getError());

                when(promptBuilder.buildPrompt(any(InvestigationRequest.class), nullable(String.class))).thenReturn("prompt text");
                when(failureFeedbackService.getClassification(anyString())).thenReturn(Optional.empty());

                String freshJson = "{"
                        + "\"classification\":\"APPLICATION_ISSUE\"," 
                        + "\"rootCause\":\"Fresh root cause from AI\"," 
                        + "\"confidence\":91," 
                        + "\"severity\":\"HIGH\"," 
                        + "\"rootCauseType\":\"CONFIRMED\""
                        + "}";
                when(ollamaClient.generateWithMetrics(anyString(), any()))
                        .thenReturn(new OllamaClient.OllamaGenerationResult(freshJson, "qwen2.5:7b", false, 10L, 100, 50));

                final List<FailureFeedback> persisted = new ArrayList<>();
                org.mockito.Mockito.doAnswer(invocation -> {
                    String fid = invocation.getArgument(0, String.class);
                    bug_investigation_agent.model.response.InvestigationResponse response =
                            invocation.getArgument(2, bug_investigation_agent.model.response.InvestigationResponse.class);
                    FailureFeedback feedback = new FailureFeedback();
                    feedback.setFailureId(fid);
                    feedback.setAiClassification(response.getClassification());
                    feedback.setRootCause(response.getRootCause());
                    feedback.setRootCauseType(response.getRootCauseType());
                        feedback.setSeverity(response.getSeverity());
                    feedback.setConfidence(response.getConfidence());
                    feedback.setSource(response.getSource());
                                        feedback.setRecommendedAction("");
                                        feedback.setSuggestedFix("");
                                        feedback.setSimilarPatterns("");
                                        feedback.setScreenshotObservation("");
                                        feedback.setEvidenceJson("[]");
                                        feedback.setMissingEvidenceJson("[]");
                                        feedback.setPreventionTipsJson("[]");
                                        feedback.setStepsToReproduceJson("[]");
                    persisted.clear();
                    persisted.add(feedback);
                    return null;
                }).when(failureFeedbackService).recordAiResult(anyString(), any(InvestigationRequest.class), any());

                when(failureFeedbackService.getFeedbackByFailureId(failureId)).thenAnswer(invocation ->
                        persisted.isEmpty() ? Optional.empty() : Optional.of(persisted.get(0)));
                when(failureFeedbackService.isAnalysisComplete(any(FailureFeedback.class))).thenAnswer(invocation -> {
                        FailureFeedback feedback = invocation.getArgument(0, FailureFeedback.class);
                        return feedback.getRootCauseType() != null
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
                });

                InvestigationService.InvestigationOutcome forcedOutcome =
                        investigationService.investigateWithMetrics(forcedRequest, "verify checkout flow");
                assertThat(forcedOutcome.response().getClassification()).isEqualTo("APPLICATION_ISSUE");
                assertThat(forcedOutcome.response().getRootCause()).isEqualTo("Fresh root cause from AI");

                InvestigationService.InvestigationOutcome normalOutcome =
                        investigationService.investigateWithMetrics(normalRequest, "verify checkout flow");
                assertThat(normalOutcome.response().getClassification()).isEqualTo("APPLICATION_ISSUE");
                assertThat(normalOutcome.response().getRootCause()).isEqualTo("Fresh root cause from AI");
                assertThat(normalOutcome.callMetrics()).isEmpty();
            }

            @Test
            void inconsistentApplicationClassification_isCorrectedToAutomationIssue() {
                InvestigationRequest request = request();
                request.setError("");
                request.setStackTrace("");

                when(failureFeedbackService.getFeedbackByFailureId(anyString())).thenReturn(Optional.empty());
                when(promptBuilder.buildPrompt(any(InvestigationRequest.class), nullable(String.class))).thenReturn("prompt text");
                when(failureFeedbackService.getClassification(anyString())).thenReturn(Optional.empty());

                String inconsistentJson = "{"
                        + "\"classification\":\"APPLICATION_ISSUE\"," 
                        + "\"rootCause\":\"Legacy locator cannot find element though button is visible and actionable\"," 
                        + "\"confidence\":95," 
                        + "\"severity\":\"MEDIUM\"," 
                        + "\"rootCauseType\":\"CONFIRMED\"," 
                        + "\"recommendedAction\":\"Update legacy locator in page object\"," 
                        + "\"suggestedFix\":\"Replace deprecated resource-id locator\"," 
                        + "\"screenshotObservation\":\"Place order button visible, no error dialog, no loading spinner\""
                        + "}";

                when(ollamaClient.generateWithMetrics(anyString(), any()))
                        .thenReturn(new OllamaClient.OllamaGenerationResult(inconsistentJson, "qwen2.5:7b", true, 10L, 100, 50));

                InvestigationService.InvestigationOutcome outcome =
                        investigationService.investigateWithMetrics(request, "verify checkout flow");

                assertThat(outcome.response().getClassification()).isEqualTo("AUTOMATION_ISSUE");
                assertThat(outcome.response().getRootCauseType()).isEqualTo("PROBABLE");
                assertThat(outcome.response().getConfidence()).isLessThanOrEqualTo(85);
            }

            @Test
            void testExpectationTypo_isClassifiedAsAutomationIssue() {
                InvestigationRequest request = request();
                request.setFailedStep("Then the heading should contain \"Everyday geer\"");
                request.setError("");
                request.setStackTrace("");

                when(failureFeedbackService.getFeedbackByFailureId(anyString())).thenReturn(Optional.empty());
                when(promptBuilder.buildPrompt(any(InvestigationRequest.class), nullable(String.class))).thenReturn("prompt text");
                when(failureFeedbackService.getClassification(anyString())).thenReturn(Optional.empty());

                String json = "{"
                        + "\"classification\":\"APPLICATION_ISSUE\","
                        + "\"rootCause\":\"Step expects Everyday geer but UI shows Everyday gear\","
                        + "\"confidence\":88,"
                        + "\"severity\":\"LOW\","
                        + "\"rootCauseType\":\"CONFIRMED\","
                        + "\"recommendedAction\":\"Update test step expected text\","
                        + "\"suggestedFix\":\"Fix gherkin assertion spelling typo\","
                        + "\"screenshotObservation\":\"Heading is properly rendered and visible; no error dialogs or loading spinners\""
                        + "}";

                when(ollamaClient.generateWithMetrics(anyString(), any()))
                        .thenReturn(new OllamaClient.OllamaGenerationResult(json, "qwen2.5:7b", true, 10L, 100, 50));

                InvestigationService.InvestigationOutcome outcome =
                        investigationService.investigateWithMetrics(request, "verify heading");

                assertThat(outcome.response().getClassification()).isEqualTo("AUTOMATION_ISSUE");
            }

            @Test
            void wrongAppCopy_isClassifiedAsDataIssue() {
                InvestigationRequest request = request();
                request.setFailedStep("Then the heading should contain \"Everyday gear\"");
                request.setError("");
                request.setStackTrace("");

                when(failureFeedbackService.getFeedbackByFailureId(anyString())).thenReturn(Optional.empty());
                when(promptBuilder.buildPrompt(any(InvestigationRequest.class), nullable(String.class))).thenReturn("prompt text");
                when(failureFeedbackService.getClassification(anyString())).thenReturn(Optional.empty());

                String json = "{"
                        + "\"classification\":\"APPLICATION_ISSUE\","
                        + "\"rootCause\":\"App displays wrong label: Everyday geer, indicating incorrect business data/copy\","
                        + "\"confidence\":90,"
                        + "\"severity\":\"LOW\","
                        + "\"rootCauseType\":\"CONFIRMED\","
                        + "\"recommendedAction\":\"Fix CMS/content service value for storefront heading\","
                        + "\"suggestedFix\":\"Correct master data/copy from geer to gear in backend content\","
                        + "\"screenshotObservation\":\"UI shows misspelled heading geer\""
                        + "}";

                when(ollamaClient.generateWithMetrics(anyString(), any()))
                        .thenReturn(new OllamaClient.OllamaGenerationResult(json, "qwen2.5:7b", true, 10L, 100, 50));

                InvestigationService.InvestigationOutcome outcome =
                        investigationService.investigateWithMetrics(request, "verify heading");

                assertThat(outcome.response().getClassification()).isEqualTo("DATA_ISSUE");
            }

            @Test
            void connectivityOutage_isClassifiedAsEnvironmentIssue() {
                InvestigationRequest request = request();
                request.setError("ERR_CONNECTION_REFUSED: 127.0.0.1 refused to connect");
                request.setStackTrace("");

                when(failureFeedbackService.getFeedbackByFailureId(anyString())).thenReturn(Optional.empty());
                when(promptBuilder.buildPrompt(any(InvestigationRequest.class), nullable(String.class))).thenReturn("prompt text");
                when(failureFeedbackService.getClassification(anyString())).thenReturn(Optional.empty());

                String json = "{"
                        + "\"classification\":\"APPLICATION_ISSUE\","
                        + "\"rootCause\":\"This site can't be reached and localhost refused to connect\","
                        + "\"confidence\":95,"
                        + "\"severity\":\"CRITICAL\","
                        + "\"rootCauseType\":\"CONFIRMED\","
                        + "\"recommendedAction\":\"Verify local service is running on port 8081 and check backend logs\","
                        + "\"suggestedFix\":\"Start dependent storefront service before tests\","
                        + "\"screenshotObservation\":\"Browser shows ERR_CONNECTION_REFUSED and no app UI is visible\""
                        + "}";

                when(ollamaClient.generateWithMetrics(anyString(), any()))
                        .thenReturn(new OllamaClient.OllamaGenerationResult(json, "qwen2.5:7b", true, 10L, 100, 50));

                InvestigationService.InvestigationOutcome outcome =
                        investigationService.investigateWithMetrics(request, "verify storefront launch");

                assertThat(outcome.response().getClassification()).isEqualTo("ENVIRONMENT_ISSUE");
            }
}


