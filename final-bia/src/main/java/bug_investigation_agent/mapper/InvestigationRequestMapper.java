package bug_investigation_agent.mapper;

import bug_investigation_agent.model.request.InvestigationRequest;
import bug_investigation_agent.model.request.ReportFailure;
import org.springframework.stereotype.Component;

@Component
public class InvestigationRequestMapper {
    public InvestigationRequest map(ReportFailure failure) {
        InvestigationRequest request = new InvestigationRequest();
        request.setTestName(failure.getTestCaseID());
        request.setFeature(failure.getFeatureName());
        request.setScenario(failure.getScenarioName());
        request.setFailedStep(failure.getFailedStepLine());
        request.setError(failure.getErrorMessage());
        request.setStackTrace(failure.getStackTrace());
        request.setConsoleLogs(failure.getConsoleLogs());
        request.setFailureImage(failure.getFailureImage());
        request.setVideoUrl(failure.getVideoUrl());
        request.setReportType("EXTENT");
        request.setAllSteps(failure.getAllSteps());
        return request;
    }
}
