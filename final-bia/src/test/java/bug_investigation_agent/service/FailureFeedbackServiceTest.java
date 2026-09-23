package bug_investigation_agent.service;

import bug_investigation_agent.model.FailureFeedback;
import bug_investigation_agent.model.request.InvestigationRequest;
import bug_investigation_agent.model.response.FailureClassificationResponse;
import bug_investigation_agent.model.response.InvestigationResponse;
import bug_investigation_agent.repository.FailureFeedbackRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
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

    private FailureFeedbackRepository repository;
    private FailureFeedbackService failureFeedbackService;

    @BeforeEach
    void setUp() {
        File dbFile = new File(tempDir, "feedback-test.db");
        repository = new FailureFeedbackRepository(dbFile.getAbsolutePath());
        failureFeedbackService = new FailureFeedbackService(repository, new ObjectMapper());
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

    @Test
    void recordAiResult_persistsAllRichFieldsAndJsonLists() {
        InvestigationResponse response = new InvestigationResponse();
        response.setClassification("AUTOMATION_ISSUE");
        response.setRootCause("Locator mismatch in checkout screen");
        response.setRootCauseType("PROBABLE");
        response.setConfidence(88);
        response.setSeverity("HIGH");
        response.setRecommendedAction("Fix locator in CheckoutPage.waitForOrderSummary");
        response.setSuggestedFix("Use stable resource-id with explicit wait");
        response.setSimilarPatterns("Timeout while loading checkout widgets");
        response.setScreenshotObservation("Spinner still visible with missing order summary card");
        response.setSource(FailureFeedbackService.SOURCE_AI);
        response.setEvidence(List.of("TimeoutException in step 5", "Checkout summary element absent"));
        response.setMissingEvidence(List.of("Appium device logs"));
        response.setPreventionTips(List.of("Add sync guard", "Use deterministic locators"));
        response.setStepsToReproduce(List.of("Login", "Navigate to cart", "Tap checkout"));

        failureFeedbackService.recordAiResult("f-rich", request(), response);

        FailureFeedback saved = failureFeedbackService.getFeedbackByFailureId("f-rich").orElseThrow();
        assertThat(saved.getAiClassification()).isEqualTo("AUTOMATION_ISSUE");
        assertThat(saved.getRootCause()).isEqualTo("Locator mismatch in checkout screen");
        assertThat(saved.getRootCauseType()).isEqualTo("PROBABLE");
        assertThat(saved.getSeverity()).isEqualTo("HIGH");
        assertThat(saved.getRecommendedAction()).isEqualTo("Fix locator in CheckoutPage.waitForOrderSummary");
        assertThat(saved.getSuggestedFix()).isEqualTo("Use stable resource-id with explicit wait");
        assertThat(saved.getSimilarPatterns()).isEqualTo("Timeout while loading checkout widgets");
        assertThat(saved.getScreenshotObservation()).isEqualTo("Spinner still visible with missing order summary card");
        assertThat(saved.getSource()).isEqualTo(FailureFeedbackService.SOURCE_AI);

        assertThat(saved.getEvidenceJson()).isEqualTo("[\"TimeoutException in step 5\",\"Checkout summary element absent\"]");
        assertThat(saved.getMissingEvidenceJson()).isEqualTo("[\"Appium device logs\"]");
        assertThat(saved.getPreventionTipsJson()).isEqualTo("[\"Add sync guard\",\"Use deterministic locators\"]");
        assertThat(saved.getStepsToReproduceJson()).isEqualTo("[\"Login\",\"Navigate to cart\",\"Tap checkout\"]");
    }

    @Test
    void oldSchemaRecord_isMigratedAndStillReadable() throws Exception {
        File oldDbFile = new File(tempDir, "feedback-old-schema.db");
        String jdbc = "jdbc:sqlite:" + oldDbFile.getAbsolutePath();

        try (Connection connection = DriverManager.getConnection(jdbc);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS failure_feedback (
                        failure_id TEXT PRIMARY KEY,
                        scenario_name TEXT,
                        feature_name TEXT,
                        failed_step_line TEXT,
                        normalized_step TEXT,
                        normalized_error TEXT,
                        exception_type TEXT,
                        locator TEXT,
                        stack_trace_pattern TEXT,
                        ai_classification TEXT,
                        human_classification TEXT,
                        root_cause TEXT,
                        confidence INTEGER,
                        created_at TEXT,
                        updated_at TEXT
                    )
                    """);
            statement.execute("""
                    INSERT INTO failure_feedback (
                        failure_id, scenario_name, feature_name, failed_step_line, normalized_step,
                        normalized_error, exception_type, locator, stack_trace_pattern,
                        ai_classification, human_classification, root_cause, confidence,
                        created_at, updated_at
                    ) VALUES (
                        'old-row-1', 'Scenario Old', 'Feature Old', 'Then old step', 'old step',
                        'assertion failed', 'AssertionError', '', 'stack frame',
                        'AUTOMATION_ISSUE', NULL, 'Old persisted cause', 70,
                        '2026-09-20T10:00:00Z', '2026-09-20T10:00:00Z'
                    )
                    """);
        }

        FailureFeedbackRepository migratedRepo = new FailureFeedbackRepository(oldDbFile.getAbsolutePath());
        FailureFeedbackService migratedService = new FailureFeedbackService(migratedRepo, new ObjectMapper());

        FailureFeedback existing = migratedService.getFeedbackByFailureId("old-row-1").orElseThrow();
        assertThat(existing.getFailureId()).isEqualTo("old-row-1");
        assertThat(existing.getAiClassification()).isEqualTo("AUTOMATION_ISSUE");
        assertThat(existing.getRootCause()).isEqualTo("Old persisted cause");
        assertThat(existing.getRootCauseType()).isNull();
        assertThat(existing.getEvidenceJson()).isNull();
        assertThat(existing.getSuggestedFix()).isNull();
        assertThat(migratedService.isAnalysisComplete(existing)).isFalse();
    }

    @Test
    void emptyOptionalLists_arePersistedAndMarkedComplete() {
        InvestigationResponse response = new InvestigationResponse();
        response.setClassification("AUTOMATION_ISSUE");
        response.setRootCause("Valid root cause");
        response.setRootCauseType("PROBABLE");
        response.setConfidence(70);
        response.setSeverity("MEDIUM");
        response.setRecommendedAction("");
        response.setSuggestedFix("");
        response.setSimilarPatterns("");
        response.setScreenshotObservation("");
        response.setSource(FailureFeedbackService.SOURCE_AI);
        response.setEvidence(List.of());
        response.setMissingEvidence(List.of());
        response.setPreventionTips(List.of());
        response.setStepsToReproduce(List.of());

        failureFeedbackService.recordAiResult("f-empty", request(), response);

        FailureFeedback saved = failureFeedbackService.getFeedbackByFailureId("f-empty").orElseThrow();
        assertThat(saved.getEvidenceJson()).isEqualTo("[]");
        assertThat(saved.getMissingEvidenceJson()).isEqualTo("[]");
        assertThat(saved.getPreventionTipsJson()).isEqualTo("[]");
        assertThat(saved.getStepsToReproduceJson()).isEqualTo("[]");
        assertThat(failureFeedbackService.isAnalysisComplete(saved)).isTrue();
    }

    @Test
    void blankRootCause_fromAiIsMarkedIncompleteForEnrichment() {
        InvestigationResponse response = new InvestigationResponse();
        response.setClassification("UNKNOWN");
        response.setRootCause("");
        response.setRootCauseType("UNKNOWN");
        response.setConfidence(40);
        response.setSeverity("LOW");
        response.setRecommendedAction("");
        response.setSuggestedFix("");
        response.setSimilarPatterns("");
        response.setScreenshotObservation("No screenshot provided");
        response.setSource(FailureFeedbackService.SOURCE_AI);
        response.setEvidence(List.of("Assertion failed"));
        response.setMissingEvidence(List.of("Device logs"));
        response.setPreventionTips(List.of("Improve assertion robustness"));
        response.setStepsToReproduce(List.of());

        failureFeedbackService.recordAiResult("f-blank-root", request(), response);

        FailureFeedback saved = failureFeedbackService.getFeedbackByFailureId("f-blank-root").orElseThrow();
        assertThat(saved.getRootCause()).isEmpty();
        assertThat(failureFeedbackService.isAnalysisComplete(saved)).isFalse();
    }
}

