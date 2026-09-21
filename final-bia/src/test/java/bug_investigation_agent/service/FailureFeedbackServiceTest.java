package bug_investigation_agent.service;

import bug_investigation_agent.model.FailureFeedback;
import bug_investigation_agent.model.request.InvestigationRequest;
import bug_investigation_agent.model.response.FailureClassificationResponse;
import bug_investigation_agent.model.response.InvestigationResponse;
import bug_investigation_agent.repository.FailureFeedbackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the human-classification-feedback foundation: saving/retrieving a human correction, the
 * AI classification never being overwritten by a correction, the effective classification
 * reflecting the human correction, input validation, and the source transitioning to
 * HUMAN_CORRECTED. Uses a real (temp-file) SQLite-backed {@link FailureFeedbackRepository} rather
 * than a mock, since persistence correctness (upsert semantics) is exactly what this feature adds.
 */
class FailureFeedbackServiceTest {

    @TempDir
    File tempDir;

    private FailureFeedbackService failureFeedbackService;

    @BeforeEach
    void setUp() {
        File dbFile = new File(tempDir, "feedback-test.db");
        FailureFeedbackRepository repository = new FailureFeedbackRepository(dbFile.getAbsolutePath());
        failureFeedbackService = new FailureFeedbackService(repository);
    }

    private InvestigationRequest request() {
        InvestigationRequest request = new InvestigationRequest();
        request.setScenario("Scenario A");
        request.setFeature("Feature A");
        request.setFailedStep("And user clicks submit");
        request.setError("java.lang.AssertionError: expected [true] but found [false]");
        request.setStackTrace("at com.example.Steps.submit(Steps.java:10)");
        return request;
    }

    private InvestigationResponse aiResponse(String classification) {
        InvestigationResponse response = new InvestigationResponse();
        response.setClassification(classification);
        response.setRootCause("Some root cause");
        response.setConfidence(80);
        return response;
    }

    // ------------------------------------------------------------------------------------------
    // 1. Save human classification.
    // ------------------------------------------------------------------------------------------
    @Test
    void savesHumanClassification() {
        failureFeedbackService.recordAiResult("f1", request(), aiResponse("AUTOMATION_ISSUE"));

        FailureClassificationResponse saved =
                failureFeedbackService.saveHumanClassification("f1", "APPLICATION_ISSUE");

        assertThat(saved.getHumanClassification()).isEqualTo("APPLICATION_ISSUE");
    }

    // ------------------------------------------------------------------------------------------
    // 2. Retrieve saved classification.
    // ------------------------------------------------------------------------------------------
    @Test
    void retrievesSavedClassification() {
        failureFeedbackService.recordAiResult("f2", request(), aiResponse("AUTOMATION_ISSUE"));
        failureFeedbackService.saveHumanClassification("f2", "DATA_ISSUE");

        Optional<FailureClassificationResponse> retrieved = failureFeedbackService.getClassification("f2");

        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getHumanClassification()).isEqualTo("DATA_ISSUE");
        assertThat(retrieved.get().getAiClassification()).isEqualTo("AUTOMATION_ISSUE");
    }

    // ------------------------------------------------------------------------------------------
    // 3. AI classification remains unchanged after human correction.
    // ------------------------------------------------------------------------------------------
    @Test
    void aiClassificationNeverOverwrittenByHumanCorrection() {
        failureFeedbackService.recordAiResult("f3", request(), aiResponse("AUTOMATION_ISSUE"));
        failureFeedbackService.saveHumanClassification("f3", "APPLICATION_ISSUE");
        failureFeedbackService.saveHumanClassification("f3", "ENVIRONMENT_ISSUE");

        FailureClassificationResponse result = failureFeedbackService.getClassification("f3").orElseThrow();

        assertThat(result.getAiClassification()).isEqualTo("AUTOMATION_ISSUE");
        assertThat(result.getHumanClassification()).isEqualTo("ENVIRONMENT_ISSUE");
    }

    // ------------------------------------------------------------------------------------------
    // 4. Effective classification becomes human classification.
    // ------------------------------------------------------------------------------------------
    @Test
    void effectiveClassificationBecomesHumanClassification() {
        failureFeedbackService.recordAiResult("f4", request(), aiResponse("AUTOMATION_ISSUE"));

        FailureClassificationResponse beforeCorrection = failureFeedbackService.getClassification("f4").orElseThrow();
        assertThat(beforeCorrection.getEffectiveClassification()).isEqualTo("AUTOMATION_ISSUE");

        FailureClassificationResponse afterCorrection =
                failureFeedbackService.saveHumanClassification("f4", "API_ISSUE");
        assertThat(afterCorrection.getEffectiveClassification()).isEqualTo("API_ISSUE");
    }

    // ------------------------------------------------------------------------------------------
    // 5. Invalid classification is rejected.
    // ------------------------------------------------------------------------------------------
    @Test
    void invalidClassificationIsRejected() {
        failureFeedbackService.recordAiResult("f5", request(), aiResponse("AUTOMATION_ISSUE"));

        assertThatThrownBy(() -> failureFeedbackService.saveHumanClassification("f5", "NOT_A_REAL_CLASSIFICATION"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------------------
    // 6. Missing humanClassification is rejected.
    // ------------------------------------------------------------------------------------------
    @Test
    void missingHumanClassificationIsRejected() {
        failureFeedbackService.recordAiResult("f6", request(), aiResponse("AUTOMATION_ISSUE"));

        assertThatThrownBy(() -> failureFeedbackService.saveHumanClassification("f6", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> failureFeedbackService.saveHumanClassification("f6", "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------------------
    // 7. Updating an existing correction works.
    // ------------------------------------------------------------------------------------------
    @Test
    void updatingExistingCorrectionWorks() {
        failureFeedbackService.recordAiResult("f7", request(), aiResponse("AUTOMATION_ISSUE"));
        failureFeedbackService.saveHumanClassification("f7", "DATA_ISSUE");
        failureFeedbackService.saveHumanClassification("f7", "NETWORK_ISSUE");

        FailureClassificationResponse result = failureFeedbackService.getClassification("f7").orElseThrow();

        assertThat(result.getHumanClassification()).isEqualTo("NETWORK_ISSUE");
    }

    // ------------------------------------------------------------------------------------------
    // 8. Source changes to HUMAN_CORRECTED.
    // ------------------------------------------------------------------------------------------
    @Test
    void sourceChangesToHumanCorrected() {
        failureFeedbackService.recordAiResult("f8", request(), aiResponse("AUTOMATION_ISSUE"));

        FailureClassificationResponse beforeCorrection = failureFeedbackService.getClassification("f8").orElseThrow();
        assertThat(beforeCorrection.getSource()).isEqualTo(FailureFeedbackService.SOURCE_AI);

        FailureClassificationResponse afterCorrection =
                failureFeedbackService.saveHumanClassification("f8", "APPLICATION_ISSUE");
        assertThat(afterCorrection.getSource()).isEqualTo(FailureFeedbackService.SOURCE_HUMAN_CORRECTED);
    }

    // ------------------------------------------------------------------------------------------
    // Extra: correcting a failure with no prior AI baseline still works (aiClassification null).
    // ------------------------------------------------------------------------------------------
    @Test
    void correctionWithoutPriorAiBaselineStillWorks() {
        FailureClassificationResponse saved =
                failureFeedbackService.saveHumanClassification("f9-no-baseline", "UNKNOWN");

        assertThat(saved.getAiClassification()).isNull();
        assertThat(saved.getHumanClassification()).isEqualTo("UNKNOWN");
        assertThat(saved.getEffectiveClassification()).isEqualTo("UNKNOWN");
        assertThat(saved.getSource()).isEqualTo(FailureFeedbackService.SOURCE_HUMAN_CORRECTED);
    }

    // ------------------------------------------------------------------------------------------
    // Extra: blank failureId is rejected.
    // ------------------------------------------------------------------------------------------
    @Test
    void blankFailureIdIsRejected() {
        assertThatThrownBy(() -> failureFeedbackService.saveHumanClassification("", "AUTOMATION_ISSUE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------------------
    // Extra: recording a second AI result for the same failureId never clobbers an existing
    // human correction.
    // ------------------------------------------------------------------------------------------
    @Test
    void reRecordingAiResultDoesNotClobberExistingHumanCorrection() {
        failureFeedbackService.recordAiResult("f10", request(), aiResponse("AUTOMATION_ISSUE"));
        failureFeedbackService.saveHumanClassification("f10", "APPLICATION_ISSUE");

        // Same failure investigated again by the AI (e.g. re-uploaded report).
        failureFeedbackService.recordAiResult("f10", request(), aiResponse("AUTOMATION_ISSUE"));

        FailureClassificationResponse result = failureFeedbackService.getClassification("f10").orElseThrow();
        assertThat(result.getHumanClassification()).isEqualTo("APPLICATION_ISSUE");
        assertThat(result.getSource()).isEqualTo(FailureFeedbackService.SOURCE_HUMAN_CORRECTED);
    }
}

