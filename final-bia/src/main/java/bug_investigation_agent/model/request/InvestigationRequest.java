package bug_investigation_agent.model.request;

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
    public void setTestName(String testName) { this.testName = testName; }
    public String getFeature() { return feature; }
    public void setFeature(String feature) { this.feature = feature; }
    public String getScenario() { return scenario; }
    public void setScenario(String scenario) { this.scenario = scenario; }
    public String getFailedStep() { return failedStep; }
    public void setFailedStep(String failedStep) { this.failedStep = failedStep; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getStackTrace() { return stackTrace; }
    public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }
    public String getConsoleLogs() { return consoleLogs; }
    public void setConsoleLogs(String consoleLogs) { this.consoleLogs = consoleLogs; }
    public String getFailureImage() { return failureImage; }
    public void setFailureImage(String failureImage) { this.failureImage = failureImage; }
    public String getVideoUrl() { return videoUrl; }
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }
    public String getReportType() { return reportType; }
    public void setReportType(String reportType) { this.reportType = reportType; }

    public Boolean getForceReanalysis() { return forceReanalysis; }
    public void setForceReanalysis(Boolean forceReanalysis) { this.forceReanalysis = forceReanalysis; }

    public boolean isForceReanalysisEnabled() {
        return Boolean.TRUE.equals(forceReanalysis);
    }
}
