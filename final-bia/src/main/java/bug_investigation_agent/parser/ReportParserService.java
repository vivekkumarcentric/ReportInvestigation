package bug_investigation_agent.parser;

import bug_investigation_agent.model.request.ReportFailure;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic parser for the existing Extent HTML report format.
 * This is the source of truth for report extraction. AI is not involved here.
 */
@Service
public class ReportParserService {

    public List<ReportFailure> parseExtentReport(String html) {
        List<ReportFailure> failures = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return failures;
        }

        Document doc = Jsoup.parse(html);
        Elements testItems = doc.select("li.test-item");

        for (Element testItem : testItems) {
            String status = testItem.attr("status");
            if (!"fail".equalsIgnoreCase(status) && !"error".equalsIgnoreCase(status)) {
                continue;
            }

            Element failedStep = testItem.selectFirst(".step.fail-bg");
            if (failedStep == null) {
                failedStep = testItem.selectFirst(".step.error-bg");
            }
            if (failedStep == null) {
                continue;
            }

            ReportFailure failure = new ReportFailure();
            failure.setTestCaseID(cleanText(testItem.attr("test-id")));

            Element scenario = testItem.selectFirst(".test-detail .name");
            failure.setScenarioName(scenario == null ? "" : cleanText(scenario.text()));

            Element stepName = failedStep.selectFirst("span");
            failure.setFailedStepLine(
                    stepName == null ? cleanText(failedStep.text()) : cleanText(stepName.text())
            );

            // Extract ALL steps from this test scenario
            Elements allStepElements = testItem.select(".step");
            StringBuilder allStepsBuilder = new StringBuilder();
            int stepNum = 1;
            for (Element step : allStepElements) {
                String stepStatus;
                if (step.hasClass("pass-bg")) {
                    stepStatus = "[PASS] ";
                } else if (step.hasClass("fail-bg")) {
                    stepStatus = "[FAIL] ";
                } else if (step.hasClass("error-bg")) {
                    stepStatus = "[ERROR] ";
                } else if (step.hasClass("skip-bg")) {
                    stepStatus = "[SKIP] ";
                } else if (step.hasClass("warning-bg")) {
                    stepStatus = "[WARN] ";
                } else {
                    stepStatus = "[INFO] ";
                }

                Element stepNameElem = step.selectFirst("span");
                String stepText = stepNameElem == null ? cleanText(step.text()) : cleanText(stepNameElem.text());

                if (!stepText.isBlank()) {
                    allStepsBuilder.append(stepNum).append(". ")
                                  .append(stepStatus)
                                  .append(stepText)
                                  .append("\n");
                    stepNum++;
                }
            }
            failure.setAllSteps(allStepsBuilder.toString().trim());

            Element codeBlock = failedStep.selectFirst("textarea.code-block");
            String error = "";
            if (codeBlock != null) {
                error = codeBlock.text();
                if (error.isBlank()) {
                    error = codeBlock.val();
                }
            }
            error = cleanTextPreserveNewLines(error);
            failure.setErrorMessage(error);
            failure.setStackTrace(error);

            Element screenshot = failedStep.selectFirst(
                    ".screenshots img, .screenshots a, img"
            );
            if (screenshot != null) {
                String image = screenshot.hasAttr("src")
                        ? screenshot.attr("src")
                        : screenshot.attr("href");
                failure.setFailureImage(image);
            } else {
                failure.setFailureImage("");
            }

            Element video = failedStep.selectFirst("video source");
            failure.setVideoUrl(video == null ? "" : cleanText(video.attr("src")));

            // The supplied Extent report has no dedicated console-log field.
            failure.setConsoleLogs("");
            failure.setFeatureName("");
            failures.add(failure);
        }

        return failures;
    }

    private String cleanText(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s+", " ").trim();
    }

    private String cleanTextPreserveNewLines(String value) {
        if (value == null || value.isBlank()) return "";
        return value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
                .map(String::stripTrailing)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("")
                .trim();
    }
}
