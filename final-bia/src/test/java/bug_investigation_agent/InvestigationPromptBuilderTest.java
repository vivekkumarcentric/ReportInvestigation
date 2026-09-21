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
        assertTrue(prompt.contains("PRESENT (embedded image"));
        assertFalse(prompt.contains("data:image/png;base64," + "A".repeat(1000)));
    }
}
