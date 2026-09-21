package bug_investigation_agent.controller;

import bug_investigation_agent.model.request.ReportFailure;
import bug_investigation_agent.model.request.ReportInvestigationRequest;
import bug_investigation_agent.model.response.ReportAnalysisResponse;
import bug_investigation_agent.parser.ReportParserService;
import bug_investigation_agent.service.ReportInvestigationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/reports")
public class ReportInvestigationController {
    private final ReportInvestigationService reportInvestigationService;
    private final ReportParserService reportParserService;

    public ReportInvestigationController(ReportInvestigationService reportInvestigationService,
                                         ReportParserService reportParserService) {
        this.reportInvestigationService = reportInvestigationService;
        this.reportParserService = reportParserService;
    }

    @PostMapping("/analyze")
    public ReportAnalysisResponse analyze(@RequestBody ReportInvestigationRequest request) {
        return toResponse(reportInvestigationService.analyzeReport(request));
    }

    /** Parse an existing Extent HTML report and immediately investigate every failure. */
    @PostMapping(value = "/analyze-html", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ReportAnalysisResponse analyzeHtml(@RequestBody String html) {
        List<ReportFailure> failures = reportParserService.parseExtentReport(html);
        ReportInvestigationRequest request = new ReportInvestigationRequest();
        request.setReportType("EXTENT");
        request.setFailures(failures);
        return toResponse(reportInvestigationService.analyzeReport(request));
    }

    /** Same operation for a multipart HTML file. */
    @PostMapping(value = "/analyze-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReportAnalysisResponse analyzeFile(@RequestPart("file") MultipartFile file) throws IOException {
        String html = new String(file.getBytes(), StandardCharsets.UTF_8);
        return analyzeHtml(html);
    }

    private ReportAnalysisResponse toResponse(ReportInvestigationService.ReportAnalysisResult result) {
        return new ReportAnalysisResponse(
                result.clustering().getTotalFailures(),
                result.clustering().getTotalClusters(),
                result.summary(),
                result.investigations(),
                result.clustering().getClusters()
        );
    }
}
