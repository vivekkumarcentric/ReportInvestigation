package bug_investigation_agent;

import bug_investigation_agent.model.report.FailureRecord;
import bug_investigation_agent.parser.HtmlReportParser;
import bug_investigation_agent.parser.ReportParserService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HtmlReportParserTest {

    @Test
    void shouldParseExtentFailuresUsingExistingAnalyzer() throws Exception {
        String html = Files.readString(Path.of(
                "src/test/resources/PepsiCo_Automation_SparkReport_nz-19.html"));

        HtmlReportParser parser = new HtmlReportParser(new ReportParserService());
        List<FailureRecord> failures = parser.parse(html, "EXTENT");

        assertEquals(36, failures.size());

        FailureRecord first = failures.stream()
                .filter(f -> "15,699".equals(f.getTestCaseId()))
                .findFirst()
                .orElseThrow();

        assertEquals("verify your profile screen functionalities", first.getScenarioName());
        assertEquals("And user select a photo from photo library for pepsico3.1", first.getFailedStep());
        assertTrue(first.getErrorMessage().contains("TimeoutException"));
        assertTrue(first.getErrorMessage().contains("CommonPage.userSelectPhotoBySelectingPhotoLibrary"));

        FailureRecord second = failures.stream()
                .filter(f -> "17,807".equals(f.getTestCaseId()))
                .findFirst()
                .orElseThrow();

        assertEquals("Games scenarios", second.getScenarioName());
        assertEquals("And user verify activity event name as \"Game played\" on your points breakdown screen for pepsico3.1", second.getFailedStep());
        assertTrue(second.getErrorMessage().contains("PLACED AN ORDER"));
    }
}
