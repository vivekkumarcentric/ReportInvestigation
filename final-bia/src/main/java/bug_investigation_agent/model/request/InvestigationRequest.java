package bug_investigation_agent.model.request;

import com.fasterxml.jackson.annotation.JsonSetter;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

public class InvestigationRequest {
    private String testName;
    private String feature;
    private String scenario;
    private String failedStep;
    private String error;
    private String stackTrace;
    private String consoleLogs;
    private String failureImage;
    private String videoUrl;
    private String reportType;
    private String allSteps;
    private List<Object> allStepsDetail;
    private Boolean forceReanalysis;

    public String getAllSteps() { return allSteps; }
    public void setAllSteps(String allSteps) { this.allSteps = allSteps; }

    public List<Object> getAllStepsDetail() { return allStepsDetail; }
    public void setAllStepsDetail(List<Object> allStepsDetail) { this.allStepsDetail = allStepsDetail; }

    public String getTestName() { return testName; }
    @JsonSetter
    public void setTestName(Object value) { this.testName = normalizeStringValue(value); }
    public String getFeature() { return feature; }
    @JsonSetter
    public void setFeature(Object value) { this.feature = normalizeStringValue(value); }
    public String getScenario() { return scenario; }
    @JsonSetter
    public void setScenario(Object value) { this.scenario = normalizeStringValue(value); }
    public String getFailedStep() { return failedStep; }
    @JsonSetter
    public void setFailedStep(Object value) { this.failedStep = normalizeStringValue(value); }
    public String getError() { return error; }
    @JsonSetter
    public void setError(Object value) { this.error = normalizeStringValue(value); }
    public String getStackTrace() { return stackTrace; }
    @JsonSetter
    public void setStackTrace(Object value) { this.stackTrace = normalizeStringValue(value); }
    public String getConsoleLogs() { return consoleLogs; }
    @JsonSetter
    public void setConsoleLogs(Object value) { this.consoleLogs = normalizeStringValue(value); }
    public String getFailureImage() { return failureImage; }
    @JsonSetter
    public void setFailureImage(Object value) { this.failureImage = normalizeStringValue(value); }
    public String getVideoUrl() { return videoUrl; }
    @JsonSetter
    public void setVideoUrl(Object value) { this.videoUrl = normalizeStringValue(value); }
    public String getReportType() { return reportType; }
    @JsonSetter
    public void setReportType(Object value) { this.reportType = normalizeStringValue(value); }

    public Boolean getForceReanalysis() { return forceReanalysis; }
    public void setForceReanalysis(Boolean forceReanalysis) { this.forceReanalysis = forceReanalysis; }

    public boolean isForceReanalysisEnabled() {
        return Boolean.TRUE.equals(forceReanalysis);
    }

    private static String normalizeStringValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            return str;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value.getClass().isArray()) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                String item = normalizeStringValue(Array.get(value, i));
                if (item != null && !item.isBlank()) {
                    parts.add(item);
                }
            }
            return String.join(", ", parts);
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> parts = new ArrayList<>();
            for (Object item : iterable) {
                String normalized = normalizeStringValue(item);
                if (normalized != null && !normalized.isBlank()) {
                    parts.add(normalized);
                }
            }
            return String.join(", ", parts);
        }
        return String.valueOf(value);
    }
}
