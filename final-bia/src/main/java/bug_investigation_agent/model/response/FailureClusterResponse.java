package bug_investigation_agent.model.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class FailureClusterResponse {

    private int totalFailures;

    private int totalClusters;

    private List<FailureCluster> clusters;
}