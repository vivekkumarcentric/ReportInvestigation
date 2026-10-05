package bug_investigation_agent.parser;

import bug_investigation_agent.model.request.ReportFailure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic parser for the existing Extent HTML report format.
 * This is the source of truth for report extraction. AI is not involved here.
 */
@Service
public class ReportParserService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<ReportFailure> parseExtentReport(String html) {
        List<ReportFailure> failures = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return failures;
        }

        String trimmed = html.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return parseJsonExtentReport(trimmed);
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

            Element screenshot = findScreenshotElement(testItem);
            failure.setFailureImage(resolveFailureImage(screenshot));

            Element video = failedStep.selectFirst("video source");
            failure.setVideoUrl(video == null ? "" : cleanText(video.attr("src")));

            // The supplied Extent report has no dedicated console-log field.
            failure.setConsoleLogs("");
            failure.setFeatureName("");
            failures.add(failure);
        }

        return failures;
    }

    private List<ReportFailure> parseJsonExtentReport(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<JsonNode> features = new ArrayList<>();

            if (root.isArray()) {
                for (JsonNode node : root) {
                    if (node.isObject() && node.has("children")) {
                        features.add(node);
                    }
                }
            } else if (root.isObject() && root.has("children")) {
                features.add(root);
            }

            List<ReportFailure> failures = new ArrayList<>();
            for (JsonNode featureNode : features) {
                JsonNode children = featureNode.get("children");
                if (children == null || !children.isArray()) {
                    continue;
                }

                for (JsonNode scenarioNode : children) {
                    if (!scenarioNode.isObject() || !scenarioNode.has("children")) {
                        continue;
                    }

                    String scenarioName = scenarioNode.path("name").asText("Unknown Scenario");
                    String scenarioStatus = scenarioNode.path("status").asText("").toUpperCase(Locale.ROOT);
                    if (!"FAIL".equals(scenarioStatus) && !"ERROR".equals(scenarioStatus)) {
                        continue;
                    }

                    List<JsonNode> scenarioSteps = collectScenarioStepNodes(scenarioNode.get("children"));
                    if (scenarioSteps.isEmpty()) {
                        continue;
                    }

                    int failedIndex = -1;
                    String failedStep = "";
                    String errorMessage = "";
                    for (int i = 0; i < scenarioSteps.size(); i++) {
                        JsonNode stepNode = scenarioSteps.get(i);
                        String stepStatus = stepNode.path("status").asText("").toUpperCase(Locale.ROOT);
                        if (!"FAIL".equals(stepStatus) && !"ERROR".equals(stepStatus)) {
                            continue;
                        }
                        failedIndex = i;
                        failedStep = stepNode.path("name").asText("");
                        errorMessage = extractErrorFromStep(stepNode);
                        break;
                    }

                    if (failedIndex < 0) {
                        continue;
                    }

                    ReportFailure failure = new ReportFailure();
                    failure.setTestCaseID(findTestCaseId(scenarioNode));
                    failure.setScenarioName(scenarioName);
                    failure.setFeatureName(featureNode.path("name").asText(""));
                    failure.setFailedStepLine(failedStep);
                    failure.setErrorMessage(cleanTextPreserveNewLines(errorMessage));
                    failure.setStackTrace(cleanTextPreserveNewLines(errorMessage));
                    failure.setFailureImage(findScreenshotInScenario(scenarioSteps, failedIndex));
                    failure.setVideoUrl(findVideoInScenario(scenarioSteps, failedIndex));
                    failure.setConsoleLogs("");
                    failure.setAllSteps(buildAllStepSummary(scenarioSteps));
                    failures.add(failure);
                }
            }

            return failures;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to parse Extent JSON report", ex);
        }
    }

    private List<JsonNode> collectScenarioStepNodes(JsonNode children) {
        List<JsonNode> flat = new ArrayList<>();
        if (children == null || !children.isArray()) {
            return flat;
        }

        ArrayList<JsonNode> stack = new ArrayList<>();
        for (JsonNode child : children) {
            stack.add(child);
        }

        while (!stack.isEmpty()) {
            JsonNode node = stack.remove(0);
            if (!node.isObject()) {
                continue;
            }

            JsonNode nested = node.get("children");
            boolean hasStepSignal = node.has("status") || node.has("logs") || node.has("media") || node.has("exceptions");
            if (hasStepSignal) {
                flat.add(node);
            }
            if (nested != null && nested.isArray()) {
                for (JsonNode child : nested) {
                    stack.add(child);
                }
            }
        }

        return flat.isEmpty() ? java.util.stream.StreamSupport.stream(children.spliterator(), false).toList() : flat;
    }

    private String extractErrorFromStep(JsonNode stepNode) {
        JsonNode logs = stepNode.get("logs");
        if (logs != null && logs.isArray()) {
            for (JsonNode logNode : logs) {
                if (logNode.has("status") && "FAIL".equalsIgnoreCase(logNode.path("status").asText())) {
                    if (logNode.has("details") && !logNode.path("details").asText().isBlank()) {
                        return logNode.path("details").asText();
                    }
                    JsonNode exception = logNode.get("exception");
                    if (exception != null) {
                        String stack = exception.path("stackTrace").asText();
                        if (!stack.isBlank()) {
                            return stack;
                        }
                        String message = exception.path("exception").path("detailMessage").asText();
                        if (!message.isBlank()) {
                            return message;
                        }
                    }
                }
            }
        }

        JsonNode exceptions = stepNode.get("exceptions");
        if (exceptions != null && exceptions.isArray() && !exceptions.isEmpty()) {
            JsonNode first = exceptions.get(0);
            if (first != null) {
                String stack = first.path("stackTrace").asText();
                if (!stack.isBlank()) {
                    return stack;
                }
            }
        }

        return stepNode.path("name").asText();
    }

    private String findScreenshotInScenario(List<JsonNode> steps, int failedIndex) {
        if (steps.isEmpty()) {
            return "";
        }

        for (int i = failedIndex; i < steps.size(); i++) {
            String screenshot = extractScreenshotFromNode(steps.get(i));
            if (!screenshot.isBlank()) {
                return screenshot;
            }
        }
        for (int i = 0; i < failedIndex && i < steps.size(); i++) {
            String screenshot = extractScreenshotFromNode(steps.get(i));
            if (!screenshot.isBlank()) {
                return screenshot;
            }
        }
        return "";
    }

    private String findVideoInScenario(List<JsonNode> steps, int failedIndex) {
        if (steps.isEmpty()) {
            return "";
        }
        for (int i = failedIndex; i < steps.size(); i++) {
            String video = extractVideoFromNode(steps.get(i));
            if (!video.isBlank()) {
                return video;
            }
        }
        for (int i = 0; i < failedIndex && i < steps.size(); i++) {
            String video = extractVideoFromNode(steps.get(i));
            if (!video.isBlank()) {
                return video;
            }
        }
        return "";
    }

    private String extractScreenshotFromNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            return "";
        }

        String imageCandidate = lookupImageValue(node);
        if (!imageCandidate.isBlank()) {
            return imageCandidate;
        }

        JsonNode media = node.get("media");
        if (media != null && media.isArray()) {
            for (JsonNode item : media) {
                String candidate = extractScreenshotFromNode(item);
                if (!candidate.isBlank()) {
                    return candidate;
                }
            }
        }

        JsonNode logs = node.get("logs");
        if (logs != null && logs.isArray()) {
            for (JsonNode logNode : logs) {
                String candidate = lookupImageValue(logNode);
                if (!candidate.isBlank()) {
                    return candidate;
                }
            }
        }

        return "";
    }

    private String extractVideoFromNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            return "";
        }
        JsonNode videoNode = node.get("videoUrl");
        if (videoNode != null && !videoNode.asText().isBlank()) {
            return videoNode.asText();
        }
        JsonNode media = node.get("media");
        if (media != null && media.isArray()) {
            for (JsonNode item : media) {
                String candidate = extractVideoFromNode(item);
                if (!candidate.isBlank()) {
                    return candidate;
                }
            }
        }
        return "";
    }

    private String lookupImageValue(JsonNode node) {
        if (node == null || !node.isObject()) {
            return "";
        }

        for (String key : List.of("failureImage", "failure_image", "screenshot", "screenshotBase64", "screenShot", "base64", "data", "src", "href", "url")) {
            JsonNode candidate = node.get(key);
            if (candidate == null || candidate.isNull()) {
                continue;
            }
            String text = candidate.asText();
            if (!text.isBlank()) {
                if (text.startsWith("data:image/") || text.contains("base64") || text.contains(".png") || text.contains(".jpg") || text.contains(".jpeg")) {
                    return text;
                }
                if (candidate.isTextual() && text.toLowerCase(Locale.ROOT).contains("screenshot")) {
                    return text;
                }
            }
        }

        if (node.has("mime_type") && node.path("mime_type").asText().toLowerCase(Locale.ROOT).startsWith("image/")) {
            String data = node.path("data").asText();
            if (!data.isBlank()) {
                return data;
            }
        }

        return "";
    }

    private String findTestCaseId(JsonNode scenarioNode) {
        return scenarioNode.path("match").path("location").asText("");
    }

    private String buildAllStepSummary(List<JsonNode> steps) {
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (JsonNode step : steps) {
            if (step == null || !step.isObject()) {
                continue;
            }
            String stepName = step.path("name").asText();
            if (!stepName.isBlank()) {
                String status = step.path("status").asText("UNKNOWN");
                builder.append(index++).append(". [").append(status.toUpperCase(Locale.ROOT)).append("] ").append(stepName).append("\n");
            }
        }
        return builder.toString().trim();
    }

    private Element findScreenshotElement(Element failedStep) {
        if (failedStep == null) {
            return null;
        }

        Element directImage = failedStep.selectFirst(
                "a[href^='data:image'], img[src^='data:image'], a[href*='base64'], img[src*='base64'], .screenshots a, .screenshots img"
        );
        if (directImage != null) {
            return directImage;
        }

        for (Element candidate : failedStep.select("a[href], img[src]")) {
            String href = safeAttr(candidate, "href");
            String src = safeAttr(candidate, "src");
            String parentHref = safeAttr(candidate.parent(), "href");
            String parentSrc = safeAttr(candidate.parent(), "src");

            if (isImageCandidate(href) || isImageCandidate(src) || isImageCandidate(parentHref) || isImageCandidate(parentSrc)) {
                return candidate;
            }
        }

        return null;
    }

    private boolean isImageCandidate(String value) {
        return value != null && (value.startsWith("data:image/") || value.contains("base64"));
    }

    private String resolveFailureImage(Element screenshot) {
        if (screenshot == null) {
            return "";
        }

        String candidate = firstNonBlank(
                safeAttr(screenshot, "href"),
                safeAttr(screenshot, "src"),
                safeAttr(screenshot.parent(), "href"),
                safeAttr(screenshot.parent(), "src")
        );

        if (candidate == null) {
            return "";
        }

        return candidate.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String safeAttr(Element element, String attrName) {
        if (element == null || !element.hasAttr(attrName)) {
            return null;
        }
        String value = element.attr(attrName);
        return value == null || value.isBlank() ? null : value;
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
