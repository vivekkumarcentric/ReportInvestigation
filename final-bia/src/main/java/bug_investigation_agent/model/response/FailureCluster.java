package bug_investigation_agent.model.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class FailureCluster {

    private String clusterId;

    private String clusterName;

    private String classification;

    private String commonRootCause;

    private int confidence;

    private String severity;

    private List<Integer> failureIndexes = new ArrayList<>();

    private List<String> testCaseIds = new ArrayList<>();

    private List<String> scenarios = new ArrayList<>();

    private String explanation;
}