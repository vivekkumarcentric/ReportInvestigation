package bug_investigation_agent.service;

import bug_investigation_agent.model.response.FailureCluster;
import bug_investigation_agent.model.response.FailureClusterResponse;
import bug_investigation_agent.model.response.FailureInvestigation;
import bug_investigation_agent.model.response.InvestigationResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class FailureClusteringService {

    public FailureClusterResponse clusterFailures(
            List<FailureInvestigation> investigations) {

        if (investigations == null || investigations.isEmpty()) {

            return new FailureClusterResponse(
                    0,
                    0,
                    new ArrayList<>()
            );
        }

        Map<String, List<Integer>> groupedIndexes =
                new LinkedHashMap<>();

        /*
         * Group failures using stable failure characteristics
         * instead of the complete AI-generated root cause.
         *
         * This prevents semantically identical failures from
         * being placed into different clusters simply because
         * the LLM worded the root cause differently.
         */
        for (int i = 0; i < investigations.size(); i++) {

            FailureInvestigation failure =
                    investigations.get(i);

            String signature =
                    buildSignature(failure);

            groupedIndexes
                    .computeIfAbsent(
                            signature,
                            key -> new ArrayList<>()
                    )
                    .add(i);
        }

        List<FailureCluster> clusters =
                new ArrayList<>();

        int clusterNumber = 1;

        for (Map.Entry<String, List<Integer>> entry :
                groupedIndexes.entrySet()) {

            FailureCluster cluster =
                    createCluster(
                            clusterNumber,
                            entry.getValue(),
                            investigations
                    );

            clusters.add(cluster);

            clusterNumber++;
        }

        return new FailureClusterResponse(
                investigations.size(),
                clusters.size(),
                clusters
        );
    }

    /**
     * Builds a stable signature for grouping failures.
     *
     * We intentionally do NOT use the complete AI-generated
     * rootCause because LLM responses can use different wording
     * for the same underlying problem.
     */
    private String buildSignature(
            FailureInvestigation failure) {

        if (failure == null) {
            return "UNKNOWN";
        }

        InvestigationResponse investigation =
                failure.getInvestigation();

        if (investigation == null) {
            return "UNKNOWN";
        }

        String classification =
                safe(investigation.getClassification());

        String feature =
                normalizeText(
                        failure.getFeatureName()
                );

        String failedStep =
                normalizeText(
                        failure.getFailedStepLine()
                );

        String error =
                normalizeError(
                        failure.getErrorMessage()
                );

        return classification
                + "|"
                + feature
                + "|"
                + failedStep
                + "|"
                + error;
    }

    /**
     * Normalizes normal text so that differences in
     * capitalization, punctuation and spacing do not create
     * different clusters.
     */
    private String normalizeText(String value) {

        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }

        return value
                .toLowerCase()
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Normalizes error messages.
     *
     * Dynamic timeout values such as:
     *
     * 30 seconds
     * 60 seconds
     * 120 seconds
     *
     * are converted to the same "timeout" value.
     */
    private String normalizeError(String error) {

        if (error == null || error.isBlank()) {
            return "UNKNOWN";
        }

        String normalized =
                error
                        .toLowerCase()
                        .replaceAll(
                                "[^a-z0-9 ]",
                                " "
                        )
                        .replaceAll(
                                "\\s+",
                                " "
                        )
                        .trim();

        normalized =
                normalized.replaceAll(
                        "\\d+\\s*seconds?",
                        "timeout"
                );

        return normalized;
    }

    private FailureCluster createCluster(
            int clusterNumber,
            List<Integer> indexes,
            List<FailureInvestigation> investigations) {

        FailureCluster cluster =
                new FailureCluster();

        cluster.setClusterId(
                String.format(
                        "CLUSTER-%03d",
                        clusterNumber
                )
        );

        cluster.setFailureIndexes(indexes);

        Set<String> testCaseIds =
                new LinkedHashSet<>();

        Set<String> scenarios =
                new LinkedHashSet<>();

        for (Integer index : indexes) {

            FailureInvestigation failure =
                    investigations.get(index);

            if (failure.getTestCaseID() != null &&
                    !failure.getTestCaseID().isBlank()) {

                testCaseIds.add(
                        failure.getTestCaseID()
                );
            }

            if (failure.getScenarioName() != null &&
                    !failure.getScenarioName().isBlank()) {

                scenarios.add(
                        failure.getScenarioName()
                );
            }
        }

        cluster.setTestCaseIds(
                new ArrayList<>(testCaseIds)
        );

        cluster.setScenarios(
                new ArrayList<>(scenarios)
        );

        /*
         * Use the first failure as the representative
         * failure for cluster-level information.
         */
        FailureInvestigation first =
                investigations.get(
                        indexes.get(0)
                );

        InvestigationResponse firstInvestigation =
                first.getInvestigation();

        if (firstInvestigation != null) {

            cluster.setClassification(
                    firstInvestigation.getClassification()
            );

            cluster.setCommonRootCause(
                    firstInvestigation.getRootCause()
            );

            cluster.setConfidence(
                    calculateAverageConfidence(
                            indexes,
                            investigations
                    )
            );

            cluster.setSeverity(
                    determineSeverity(
                            indexes,
                            investigations
                    )
            );

            cluster.setClusterName(
                    buildClusterName(first)
            );
        }

        cluster.setExplanation(
                indexes.size()
                        + " failure(s) were grouped based on "
                        + "similar failure characteristics."
        );

        return cluster;
    }

    private int calculateAverageConfidence(
            List<Integer> indexes,
            List<FailureInvestigation> investigations) {

        return (int) indexes.stream()
                .map(investigations::get)
                .map(FailureInvestigation::getInvestigation)
                .filter(Objects::nonNull)
                .mapToInt(
                        InvestigationResponse::getConfidence
                )
                .average()
                .orElse(0);
    }

    private String determineSeverity(
            List<Integer> indexes,
            List<FailureInvestigation> investigations) {

        List<String> severities =
                indexes.stream()
                        .map(investigations::get)
                        .map(FailureInvestigation::getInvestigation)
                        .filter(Objects::nonNull)
                        .map(InvestigationResponse::getSeverity)
                        .filter(Objects::nonNull)
                        .map(String::toUpperCase)
                        .toList();

        if (severities.contains("CRITICAL")) {
            return "CRITICAL";
        }

        if (severities.contains("HIGH")) {
            return "HIGH";
        }

        if (severities.contains("MEDIUM")) {
            return "MEDIUM";
        }

        return "LOW";
    }

    /**
     * Creates a short, dashboard-friendly cluster name.
     *
     * Example:
     *
     * Checkout - Click Pay button (API_ISSUE)
     */
    private String buildClusterName(
            FailureInvestigation failure) {

        if (failure == null) {
            return "Unknown Failure";
        }

        InvestigationResponse investigation =
                failure.getInvestigation();

        if (investigation == null) {
            return "Unknown Failure";
        }

        String classification =
                safe(
                        investigation.getClassification()
                );

        String feature =
                safe(
                        failure.getFeatureName()
                );

        String failedStep =
                safe(
                        failure.getFailedStepLine()
                );

        if (!feature.isBlank() &&
                !failedStep.isBlank()) {

            return feature
                    + " - "
                    + failedStep
                    + " ("
                    + classification
                    + ")";
        }

        if (!feature.isBlank()) {
            return feature
                    + " ("
                    + classification
                    + ")";
        }

        if (!failedStep.isBlank()) {
            return failedStep
                    + " ("
                    + classification
                    + ")";
        }

        if (!classification.isBlank()) {
            return classification;
        }

        return "Unknown Failure";
    }

    private String safe(String value) {

        return value == null
                ? ""
                : value.trim();
    }
}