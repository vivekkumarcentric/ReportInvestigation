package bug_investigation_agent.service;

import bug_investigation_agent.client.OllamaClient;
import bug_investigation_agent.model.request.InvestigationRequest;
import bug_investigation_agent.model.response.FailureClassificationResponse;
import bug_investigation_agent.prompt.InvestigationPromptBuilder;
import bug_investigation_agent.util.FailureIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests the historical-human-classification short-circuit in
 * {@link InvestigationService#investigateWithMetrics(InvestigationRequest, String)}: when a
 * failure has already been human-classified (looked up by the same stable {@code failureId} used
 * for feedback persistence), the historical classification must be returned directly - with
 * {@code source = HISTORICAL} and zero Ollama calls - instead of re-invoking the AI.
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

        FailureClassificationResponse historical = new FailureClassificationResponse(
                failureId,
                "AUTOMATION_ISSUE",          // original AI classification
                "APPLICATION_ISSUE",         // human correction
                "APPLICATION_ISSUE",         // effective classification
                FailureFeedbackService.SOURCE_HUMAN_CORRECTED);

        when(failureFeedbackService.getClassification(failureId)).thenReturn(Optional.of(historical));

        InvestigationService.InvestigationOutcome outcome =
                investigationService.investigateWithMetrics(request, "verify checkout flow");

        assertThat(outcome.response().getFailureId()).isEqualTo(failureId);
        assertThat(outcome.response().getAiClassification()).isEqualTo("AUTOMATION_ISSUE");
        assertThat(outcome.response().getHumanClassification()).isEqualTo("APPLICATION_ISSUE");
        assertThat(outcome.response().getClassification()).isEqualTo("APPLICATION_ISSUE");
        assertThat(outcome.response().getSource()).isEqualTo(FailureFeedbackService.SOURCE_HISTORICAL);
        assertThat(outcome.callMetrics()).isEmpty();

        // The whole point of the short-circuit: Ollama (and prompt building) must never be
        // invoked when a historical human classification already exists.
        verifyNoInteractions(ollamaClient);
        verifyNoInteractions(promptBuilder);
    }

    @Test
    void noHistoricalClassification_fallsThroughToOllama() {
        InvestigationRequest request = request();
        String failureId = FailureIdGenerator.generate(
                request.getScenario(), request.getFeature(), request.getFailedStep(), request.getError());

        when(failureFeedbackService.getClassification(failureId)).thenReturn(Optional.empty());
        when(promptBuilder.buildPrompt(any(InvestigationRequest.class))).thenReturn("prompt text");

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
}


