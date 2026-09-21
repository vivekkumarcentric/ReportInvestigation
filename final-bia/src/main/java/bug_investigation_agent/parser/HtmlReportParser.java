package bug_investigation_agent.parser;

import bug_investigation_agent.model.report.FailureRecord;
import bug_investigation_agent.model.request.ReportFailure;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Public adapter used by tests/UI code. The actual HTML parsing is deliberately
 * kept in ReportParserService so there is only one source of truth for Extent.
 */
@Component
public class HtmlReportParser {

    private final ReportParserService reportParserService;

    public HtmlReportParser(ReportParserService reportParserService) {
        this.reportParserService = reportParserService;
    }

    public List<FailureRecord> parse(String html, String reportType) {
        if (html == null || html.isBlank()) {
            return Collections.emptyList();
        }

        if (!"EXTENT".equalsIgnoreCase(reportType)) {
            return Collections.emptyList();
        }

        return reportParserService.parseExtentReport(html)
                .stream()
                .map(this::toFailureRecord)
                .toList();
    }

    private FailureRecord toFailureRecord(ReportFailure failure) {
        return FailureRecord.builder()
                .testCaseId(failure.getTestCaseID())
                .scenarioName(failure.getScenarioName())
                .failedStep(failure.getFailedStepLine())
                .errorMessage(failure.getErrorMessage())
                .featureName(failure.getFeatureName())
                .failureImage(failure.getFailureImage())
                .videoUrl(failure.getVideoUrl())
                .reportType("EXTENT")
                .build();
    }
}
