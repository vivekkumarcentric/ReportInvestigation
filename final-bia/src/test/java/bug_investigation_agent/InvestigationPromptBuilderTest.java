package bug_investigation_agent;

import bug_investigation_agent.model.request.InvestigationRequest;
import bug_investigation_agent.prompt.InvestigationPromptBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InvestigationPromptBuilderTest {
    @Test
    void shouldBuildEvidenceRichPromptWithoutEmbeddingBase64() {
        InvestigationRequest request = new InvestigationRequest();
        request.setTestName("15,699");
        request.setScenario("verify your profile screen functionalities");
        request.setFailedStep("And user select a photo from photo library for pepsico3.1");
        request.setError("TimeoutException: element not found");
        request.setStackTrace("CommonPage.userSelectPhotoBySelectingPhotoLibrary(CommonPage.java:2492)");
        request.setFailureImage("data:image/png;base64," + "A".repeat(1000));
        request.setReportType("EXTENT");

        String prompt = new InvestigationPromptBuilder().buildPrompt(request);

        assertTrue(prompt.contains("15,699"));
        assertTrue(prompt.contains("CommonPage.userSelectPhotoBySelectingPhotoLibrary"));
        assertTrue(prompt.contains("AN IMAGE IS ATTACHED TO THIS REQUEST"));
        assertFalse(prompt.contains("data:image/png;base64," + "A".repeat(1000)));
    }

    @Test
    void shouldIncludeEnvironmentClassificationPrecedenceForConnectionFailures() {
        InvestigationRequest request = new InvestigationRequest();
        request.setScenario("Northstar storefront unavailable");
        request.setFailedStep("Given I am on the Northstar storefront");
        request.setError("ERR_CONNECTION_REFUSED: 127.0.0.1 refused to connect");
        request.setFailureImage("data:image/png;base64," + "B".repeat(1000));

        String prompt = new InvestigationPromptBuilder().buildPrompt(request);

        assertTrue(prompt.contains("CLASSIFICATION PRECEDENCE (web/service availability)"));
        assertTrue(prompt.contains("ERR_CONNECTION_REFUSED"));
        assertTrue(prompt.contains("classify as ENVIRONMENT_ISSUE or NETWORK_ISSUE"));
        assertTrue(prompt.contains("Do NOT classify these as APPLICATION_ISSUE"));
    }

    @Test
    void shouldIncludeLocatorIssueRuleWhenElementVisibleInScreenshot() {
        InvestigationRequest request = new InvestigationRequest();
        request.setScenario("Checkout button click");
        request.setFailedStep("Then I click Checkout button");
        request.setError("NoSuchElementException: unable to locate element");
        request.setFailureImage("data:image/png;base64," + "C".repeat(1000));

        String prompt = new InvestigationPromptBuilder().buildPrompt(request);

        assertTrue(prompt.contains("If screenshot clearly shows the expected target element is present/visible"));
        assertTrue(prompt.contains("classify as AUTOMATION_ISSUE"));
        assertTrue(prompt.contains("probable locator/wait instability"));
    }
}
