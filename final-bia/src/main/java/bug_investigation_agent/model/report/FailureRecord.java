package bug_investigation_agent.model.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailureRecord {

    private String testCaseId;

    private String scenarioName;

    private String failedStep;

    private String errorMessage;

    private String featureName;

    private String failureImage;

    private String videoUrl;

    private String reportType;
}