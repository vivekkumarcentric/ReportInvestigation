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
        assertThat(outcome.response().getSource()).isEqualTo(FailureFeedbackService.SOURCE_HUMAN_CORRECTED);
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
        when(promptBuilder.buildPrompt(any(InvestigationRequest.class))).thenReturn("prompt text");
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
                assertThat(outcome.response().getSource()).isEqualTo(FailureFeedbackService.SOURCE_AI);
                assertThat(outcome.response().getRootCauseType()).isEqualTo("PROBABLE");
                assertThat(outcome.callMetrics()).isEmpty();

                verifyNoInteractions(ollamaClient);
                verifyNoInteractions(promptBuilder);
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
        when(promptBuilder.buildPrompt(any(InvestigationRequest.class))).thenReturn("prompt text");
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
        assertThat(outcome.response().getSource()).isEqualTo(FailureFeedbackService.SOURCE_AI);
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
                when(promptBuilder.buildPrompt(any(InvestigationRequest.class))).thenReturn("prompt text");
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

                when(promptBuilder.buildPrompt(any(InvestigationRequest.class))).thenReturn("prompt text");
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
            void forceReanalysis_thenNormalCall_returnsUpdatedHistoricalAnalysis() {
                InvestigationRequest forcedRequest = request();
                forcedRequest.setForceReanalysis(true);
                InvestigationRequest normalRequest = request();

                String failureId = FailureIdGenerator.generate(
                        forcedRequest.getScenario(), forcedRequest.getFeature(), forcedRequest.getFailedStep(), forcedRequest.getError());

                when(promptBuilder.buildPrompt(any(InvestigationRequest.class))).thenReturn("prompt text");
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
}


