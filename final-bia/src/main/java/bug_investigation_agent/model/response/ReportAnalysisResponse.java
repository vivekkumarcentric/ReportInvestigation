package bug_investigation_agent.model.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ReportAnalysisResponse {

    private int totalFailures;

    private int totalClusters;

    private ReportAnalysisSummary summary;

    private List<FailureInvestigation> investigations;

    private List<FailureCluster> clusters;
}