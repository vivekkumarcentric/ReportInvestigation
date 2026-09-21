package bug_investigation_agent.model.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FailureInvestigation {

    private String testCaseID;

    private String scenarioName;

    private String featureName;

    private String failedStepLine;

    private String errorMessage;

    private String failureImage;

    private String videoUrl;

    private String stackTrace;

    private String consoleLogs;

    private InvestigationResponse investigation;
}