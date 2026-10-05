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
    void shouldParseJsonExtentFailuresFromAutomationReport() throws Exception {
        String reportJson = Files.readString(Path.of(
                "src/test/resources/automation-report.json"));

        HtmlReportParser parser = new HtmlReportParser(new ReportParserService());
        List<FailureRecord> failures = parser.parse(reportJson, "EXTENT");

        assertEquals(10, failures.size());

        FailureRecord first = failures.stream()
                .filter(f -> "Changing quantity recalculates subtotal".equals(f.getScenarioName()))
                .findFirst()
                .orElseThrow();

        assertEquals("Then the heading should contain \"Everyday geer\"", first.getFailedStep());
        assertFalse(first.getErrorMessage().isBlank());
        assertTrue(first.getScenarioName().contains("Changing quantity"));
    }

    @Test
    void shouldAcceptJsonExtentInputWithoutHtmlFixtures() throws Exception {
        String reportJson = Files.readString(Path.of(
                "src/test/resources/automation-report.json"));

        HtmlReportParser parser = new HtmlReportParser(new ReportParserService());
        List<FailureRecord> failures = parser.parse(reportJson, "EXTENT");

        assertTrue(failures.stream().anyMatch(f -> f.getFeatureName() != null && !f.getFeatureName().isBlank()));
        assertTrue(failures.stream().anyMatch(f -> f.getFailedStep() != null && f.getFailedStep().contains("Everyday geer")));
    }
}
