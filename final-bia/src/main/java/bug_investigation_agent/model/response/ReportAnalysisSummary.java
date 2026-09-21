package bug_investigation_agent.model.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ReportAnalysisSummary {

    private int automationIssues;

    private int applicationIssues;

    private int apiIssues;

    private int dataIssues;

    private int environmentIssues;

    private int networkIssues;

    private int unknownIssues;

    private int lowSeverity;

    private int mediumSeverity;

    private int highSeverity;

    private int criticalSeverity;
}