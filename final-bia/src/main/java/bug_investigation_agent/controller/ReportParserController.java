package bug_investigation_agent.controller;

import bug_investigation_agent.model.request.ReportFailure;
import bug_investigation_agent.parser.ReportParserService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import java.util.List;

@RestController
@RequestMapping("/api/reports")
public class ReportParserController {

    private final ReportParserService reportParserService;

    public ReportParserController(
            ReportParserService reportParserService) {

        this.reportParserService = reportParserService;
    }

    @PostMapping(
            value = "/parse",
            consumes = MediaType.TEXT_PLAIN_VALUE
    )
    public List<ReportFailure> parseReport(
            @RequestBody String html) {

        return reportParserService.parseExtentReport(html);
    }

    @PostMapping(
            value = "/parse-file",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public List<ReportFailure> parseFile(
            @RequestPart("file") MultipartFile file) throws IOException {

        String html = new String(file.getBytes(), StandardCharsets.UTF_8);
        return reportParserService.parseExtentReport(html);
    }
}