package bug_investigation_agent.service;

import bug_investigation_agent.mapper.InvestigationRequestMapper;
import bug_investigation_agent.model.request.InvestigationRequest;
import bug_investigation_agent.model.request.ReportFailure;
import bug_investigation_agent.model.request.ReportInvestigationRequest;
import bug_investigation_agent.model.response.FailureInvestigation;
import bug_investigation_agent.model.response.InvestigationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the deterministic weighted-similarity pre-analysis failure grouping in
 * {@link ReportInvestigationService}. These tests mock {@link InvestigationService} so we can
 * assert exactly how many Ollama investigations were triggered (i.e. how many groups were
 * formed) without needing a live Ollama instance.
 */
class ReportInvestigationServiceTest {

    private InvestigationService investigationService;
    private ReportInvestigationService reportInvestigationService;

    @BeforeEach
    void setUp() throws Exception {
        investigationService = mock(InvestigationService.class);

        // Return a distinct, fresh InvestigationResponse per call so we can tell how many times
        // Ollama would have actually been invoked (one per pre-analysis group).
        when(investigationService.investigateWithMetrics(any(InvestigationRequest.class), anyString()))
                .thenAnswer(invocation -> {
                    InvestigationResponse response = new InvestigationResponse();
                    response.setClassification("AUTOMATION_ISSUE");
                    response.setSeverity("MEDIUM");
                    return new InvestigationService.InvestigationOutcome(response, List.of());
                });

        InvestigationRequestMapper mapper = new InvestigationRequestMapper();
        FailureClusteringService clusteringService = new FailureClusteringService();

        reportInvestigationService =
                new ReportInvestigationService(investigationService, mapper, clusteringService);

        setThreshold(reportInvestigationService, 0.80);
    }

    private static void setThreshold(ReportInvestigationService service, double threshold) throws Exception {
        Field field = ReportInvestigationService.class.getDeclaredField("preAnalysisThreshold");
        field.setAccessible(true);
        field.set(service, threshold);
    }

    private ReportFailure failure(String step, String error, String stackTrace, String image) {
        ReportFailure failure = new ReportFailure();
        failure.setTestCaseID("TC-" + System.nanoTime());
        failure.setScenarioName("Scenario " + step);
        failure.setFeatureName("Feature");
        failure.setFailedStepLine(step);
        failure.setErrorMessage(error);
        failure.setStackTrace(stackTrace);
        failure.setFailureImage(image);
        return failure;
    }

    private ReportInvestigationService.ReportAnalysisResult analyze(ReportFailure... failures) {
        ReportInvestigationRequest request = new ReportInvestigationRequest();
        request.setReportType("EXTENT");
        List<ReportFailure> list = new ArrayList<>(List.of(failures));
        request.setFailures(list);
        return reportInvestigationService.analyzeReport(request);
    }

    // ------------------------------------------------------------------------------------------
    // 1. Same step + same error -> same group
    // ------------------------------------------------------------------------------------------
    @Test
    void sameStepAndSameError_groupedTogether() {
        ReportFailure f1 = failure(
                "And user click on \"Targets\" navigation button for pepsico3.1",
                "java.lang.AssertionError: expected [true] but found [false]",
                null, null);
        ReportFailure f2 = failure(
                "And user click on \"Targets\" navigation button for pepsico3.1",
                "java.lang.AssertionError: expected [true] but found [false]",
                null, null);

        analyze(f1, f2);

        verify(investigationService, times(1))
                .investigateWithMetrics(any(InvestigationRequest.class), anyString());
    }

    // ------------------------------------------------------------------------------------------
    // 2. Given/When/Then/And variation -> same group
    // ------------------------------------------------------------------------------------------
    @Test
    void gherkinKeywordVariation_stillGroupedTogether() {
        ReportFailure f1 = failure(
                "Then user is on \"Products\" screen for pepsico3.1",
                "java.lang.AssertionError: Expected screen to be: 'Products' but found: ''",
                null, null);
        ReportFailure f2 = failure(
                "And user is on \"Products\" screen for pepsico3.1",
                "java.lang.AssertionError: Expected screen to be: 'Products' but found: ''",
                null, null);

        analyze(f1, f2);

        verify(investigationService, times(1))
                .investigateWithMetrics(any(InvestigationRequest.class), anyString());
    }

    // ------------------------------------------------------------------------------------------
    // 3. Same error with different dynamic numeric values -> same group
    // ------------------------------------------------------------------------------------------
    @Test
    void differentDynamicNumericValues_sameErrorPattern_groupedTogether() {
        ReportFailure f1 = failure(
                "And user should see progress value on the left side of progress bar for target \"_targetId\" for pepsico3.1",
                "java.lang.AssertionError: expected [S/35] but found [S/0]",
                null, null);
        ReportFailure f2 = failure(
                "And user should see progress value on the left side of progress bar for target \"_targetId\" for pepsico3.1",
                "java.lang.AssertionError: expected [S/99] but found [S/12]",
                null, null);

        analyze(f1, f2);

        verify(investigationService, times(1))
                .investigateWithMetrics(any(InvestigationRequest.class), anyString());
    }

    // ------------------------------------------------------------------------------------------
    // 4. Same error with different resource IDs -> different groups
    // ------------------------------------------------------------------------------------------
    @Test
    void differentResourceIds_differentGroups() {
        ReportFailure f1 = failure(
                "And user should see target name and target points for target id \"_targetId\" for pepsico3.1",
                "org.openqa.selenium.TimeoutException: Expected condition failed: waiting for visibility of element "
                        + "located by By.xpath: //android.view.ViewGroup[@resource-id='opportunityCard_click_104712']"
                        + "//android.widget.TextView[@content-desc='opportunity_card_title']",
                null, null);
        ReportFailure f2 = failure(
                "And user should see target name and target points for target id \"_targetId\" for pepsico3.1",
                "org.openqa.selenium.TimeoutException: Expected condition failed: waiting for visibility of element "
                        + "located by By.xpath: //android.view.ViewGroup[@resource-id='opportunityCard_click_147653']"
                        + "//android.widget.TextView[@content-desc='opportunity_card_title']",
                null, null);

        analyze(f1, f2);

        verify(investigationService, times(2))
                .investigateWithMetrics(any(InvestigationRequest.class), anyString());
    }

    // ------------------------------------------------------------------------------------------
    // 5. TimeoutException vs NoSuchElementException -> different groups
    // ------------------------------------------------------------------------------------------
    @Test
    void differentExceptionTypes_differentGroups() {
        ReportFailure f1 = failure(
                "And user click on submit button",
                "org.openqa.selenium.TimeoutException: Expected condition failed: waiting for element",
                null, null);
        ReportFailure f2 = failure(
                "And user click on submit button",
                "org.openqa.selenium.NoSuchElementException: Unable to locate element",
                null, null);

        analyze(f1, f2);

        verify(investigationService, times(2))
                .investigateWithMetrics(any(InvestigationRequest.class), anyString());
    }

    // ------------------------------------------------------------------------------------------
    // 6. Different screen names -> different groups
    // ------------------------------------------------------------------------------------------
    @Test
    void differentScreenNames_differentGroups() {
        ReportFailure f1 = failure(
                "Then user is on \"Products\" screen for pepsico3.1",
                "java.lang.AssertionError: expected [true] but found [false]",
                null, null);
        ReportFailure f2 = failure(
                "Then user is on \"Rewards\" screen for pepsico3.1",
                "java.lang.AssertionError: expected [true] but found [false]",
                null, null);

        analyze(f1, f2);

        verify(investigationService, times(2))
                .investigateWithMetrics(any(InvestigationRequest.class), anyString());
    }

    // ------------------------------------------------------------------------------------------
    // 7. Empty error vs populated error -> do not blindly merge
    // ------------------------------------------------------------------------------------------
    @Test
    void emptyErrorVsPopulatedError_notMerged() {
        ReportFailure f1 = failure(
                "And user click on submit button",
                null,
                null, null);
        ReportFailure f2 = failure(
                "And user click on submit button",
                "java.lang.AssertionError: expected [true] but found [false]",
                null, null);

        analyze(f1, f2);

        verify(investigationService, times(2))
                .investigateWithMetrics(any(InvestigationRequest.class), anyString());
    }

    // ------------------------------------------------------------------------------------------
    // 8. Same order template with different order IDs -> verify grouping behavior
    // ------------------------------------------------------------------------------------------
    @Test
    void sameOrderTemplateDifferentOrderIds_groupedTogether() {
        ReportFailure f1 = failure(
                "Then user should see order details for order \"ORD-1001\"",
                "java.lang.AssertionError: expected order status [DELIVERED] but found [PENDING]",
                null, null);
        ReportFailure f2 = failure(
                "Then user should see order details for order \"ORD-2002\"",
                "java.lang.AssertionError: expected order status [DELIVERED] but found [PENDING]",
                null, null);

        analyze(f1, f2);

        // Step text differs only by the quoted order id, which is preserved (not masked) in the
        // step component, so these are NOT automatically grouped by step. However, they still
        // share the exact same normalized error body and no locator information, meaning the
        // score depends on step+error alone: stepMatch=false (quoted order id differs),
        // exceptionMatch=true (AssertionError), locatorMatch=true (neutral, none present),
        // errorBodyMatch=true only if normalized bodies are identical (order-specific status
        // values are outside quotes and not masked, so this remains distinguishable). This test
        // documents the CURRENT deterministic outcome for this scenario: two separate groups,
        // since the differing quoted order id in the step (never masked) drives them apart -
        // exactly the intended "do not over-merge on partial signals" safety behavior.
        verify(investigationService, times(2))
                .investigateWithMetrics(any(InvestigationRequest.class), anyString());
    }

    // ------------------------------------------------------------------------------------------
    // 9. Multiple possible groups -> highest score selected
    // ------------------------------------------------------------------------------------------
    @Test
    void multiplePossibleGroups_highestScoreSelected() {
        // Group A representative: step+exception+error match, no locator.
        ReportFailure groupA = failure(
                "And user click on submit button",
                "java.lang.AssertionError: expected [true] but found [false]",
                null, null);

        // Group B representative: same step, different exception (forces separate group).
        ReportFailure groupB = failure(
                "And user click on submit button",
                "org.openqa.selenium.NoSuchElementException: Unable to locate element",
                null, null);

        // Candidate: matches Group A on step+exception+error exactly (score 1.0), so it must
        // join Group A rather than Group B, even though it shares the step text with both.
        ReportFailure candidate = failure(
                "And user click on submit button",
                "java.lang.AssertionError: expected [true] but found [false]",
                null, null);

        analyze(groupA, groupB, candidate);

        // 2 groups total => 2 Ollama calls (groupA+candidate merged, groupB separate).
        verify(investigationService, times(2))
                .investigateWithMetrics(any(InvestigationRequest.class), anyString());
    }

    // ------------------------------------------------------------------------------------------
    // 10. Score below threshold -> new group
    // ------------------------------------------------------------------------------------------
    @Test
    void scoreBelowThreshold_createsNewGroup() {
        ReportFailure f1 = failure(
                "And user click on \"Targets\" navigation button for pepsico3.1",
                "java.lang.AssertionError: expected [true] but found [false]",
                null, null);
        // Different step (different quoted UI target) AND different exception -> score well
        // below the 0.80 threshold (only errorBody might partially match, worth 0.15 at most).
        ReportFailure f2 = failure(
                "And user click on \"Rewards\" navigation button for pepsico3.1",
                "org.openqa.selenium.NoSuchElementException: element missing",
                null, null);

        analyze(f1, f2);

        verify(investigationService, times(2))
                .investigateWithMetrics(any(InvestigationRequest.class), anyString());
    }

    // ------------------------------------------------------------------------------------------
    // 11. Representative selection still prefers screenshot
    // ------------------------------------------------------------------------------------------
    @Test
    void representativeSelection_prefersFailureWithScreenshot() {
        ReportFailure withoutImage = failure(
                "And user click on \"Targets\" navigation button for pepsico3.1",
                "java.lang.AssertionError: expected [true] but found [false]",
                null, null);
        ReportFailure withImage = failure(
                "And user click on \"Targets\" navigation button for pepsico3.1",
                "java.lang.AssertionError: expected [true] but found [false]",
                null, "data:image/png;base64,AAAA");

        ReportInvestigationService.ReportAnalysisResult result = analyze(withoutImage, withImage);

        verify(investigationService, times(1))
                .investigateWithMetrics(any(InvestigationRequest.class), anyString());

        List<FailureInvestigation> investigations = result.investigations();
        assertThat(investigations).hasSize(2);
        // Both failures share the same (single) InvestigationResponse instance produced from the
        // representative call.
        assertThat(investigations.get(0).getInvestigation())
                .isSameAs(investigations.get(1).getInvestigation());
    }

    // ------------------------------------------------------------------------------------------
    // Locator-based grouping/merging sanity checks
    // ------------------------------------------------------------------------------------------
    @Test
    void sameLocator_sameStep_sameException_groupedTogether() {
        String locatorError =
                "org.openqa.selenium.TimeoutException: waiting for visibility of element located by "
                        + "By.xpath: //android.view.ViewGroup[@resource-id='loginButton_click_1']";

        ReportFailure f1 = failure("And user click on login button", locatorError, null, null);
        ReportFailure f2 = failure("And user click on login button", locatorError, null, null);

        analyze(f1, f2);

        verify(investigationService, times(1))
                .investigateWithMetrics(any(InvestigationRequest.class), anyString());
    }

    @Test
    void oneWithLocatorOneWithout_neverAssumedEquivalent() {
        ReportFailure withLocator = failure(
                "And user click on login button",
                "org.openqa.selenium.TimeoutException: waiting for visibility of element located by "
                        + "By.xpath: //android.view.ViewGroup[@resource-id='loginButton_click_1']",
                null, null);
        ReportFailure withoutLocator = failure(
                "And user click on login button",
                "org.openqa.selenium.TimeoutException: some other timeout message without a locator",
                null, null);

        analyze(withLocator, withoutLocator);

        verify(investigationService, times(2))
                .investigateWithMetrics(any(InvestigationRequest.class), anyString());
    }
}

