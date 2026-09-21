package bug_investigation_agent.model.request;

import lombok.Data;

import java.util.List;

@Data
public class ReportInvestigationRequest {

    private String reportType;

    private List<ReportFailure> failures;
}