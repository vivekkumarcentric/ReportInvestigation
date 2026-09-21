package bug_investigation_agent.model.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ReportInvestigationResponse {

    private int totalFailures;

    private List<FailureInvestigation> investigations;
}