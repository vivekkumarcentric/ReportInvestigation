package bug_investigation_agent.model.request;

import lombok.Data;

@Data
public class ReportFailure {

    private String testCaseID;

    private String scenarioName;

    private String failedStepLine;

    private String errorMessage;

    private String failureImage;

    private String videoUrl;

    private String featureName;

    private String stackTrace;

    private String consoleLogs;

    private String allSteps;
}