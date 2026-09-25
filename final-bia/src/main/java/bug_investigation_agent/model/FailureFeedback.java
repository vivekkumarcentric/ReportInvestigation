package bug_investigation_agent.model;

import java.time.Instant;

/**
 * A single persisted human-classification-feedback record for one failure (keyed by
 * {@code failureId}, a stable content hash - see
 * {@link bug_investigation_agent.util.FailureIdGenerator}).
 *
 * <p>{@code aiClassification} is written once per AI investigation of this exact failure and is
 * NEVER modified by a human correction. {@code humanClassification} is null until a user
 * explicitly corrects the classification, and only that field is updated by corrections.</p>
 */
public class FailureFeedback {

    private String failureId;
    private String scenarioName;
    private String featureName;
    private String failedStepLine;
    private String normalizedStep;
    private String normalizedError;
    private String exceptionType;
    private String locator;
    private String stackTracePattern;
    private String aiClassification;
    private String humanClassification;
    private String rootCause;
    private String rootCauseType;
    private String severity;
    private String recommendedAction;
    private String suggestedFix;
    private String similarPatterns;
    private String screenshotObservation;
    private String screenshotHash;
    private String evidenceJson;
    private String missingEvidenceJson;
    private String preventionTipsJson;
    private String stepsToReproduceJson;
    private String source;
    private Integer confidence;
    private Instant createdAt;
    private Instant updatedAt;

    public String getFailureId() {
        return failureId;
    }

    public void setFailureId(String failureId) {
        this.failureId = failureId;
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public void setScenarioName(String scenarioName) {
        this.scenarioName = scenarioName;
    }

    public String getFeatureName() {
        return featureName;
    }

    public void setFeatureName(String featureName) {
        this.featureName = featureName;
    }

    public String getFailedStepLine() {
        return failedStepLine;
    }

    public void setFailedStepLine(String failedStepLine) {
        this.failedStepLine = failedStepLine;
    }

    public String getNormalizedStep() {
        return normalizedStep;
    }

    public void setNormalizedStep(String normalizedStep) {
        this.normalizedStep = normalizedStep;
    }

    public String getNormalizedError() {
        return normalizedError;
    }

    public void setNormalizedError(String normalizedError) {
        this.normalizedError = normalizedError;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public void setExceptionType(String exceptionType) {
        this.exceptionType = exceptionType;
    }

    public String getLocator() {
        return locator;
    }

    public void setLocator(String locator) {
        this.locator = locator;
    }

    public String getStackTracePattern() {
        return stackTracePattern;
    }

    public void setStackTracePattern(String stackTracePattern) {
        this.stackTracePattern = stackTracePattern;
    }

    public String getAiClassification() {
        return aiClassification;
    }

    public void setAiClassification(String aiClassification) {
        this.aiClassification = aiClassification;
    }

    public String getHumanClassification() {
        return humanClassification;
    }

    public void setHumanClassification(String humanClassification) {
        this.humanClassification = humanClassification;
    }

    public String getRootCause() {
        return rootCause;
    }

    public void setRootCause(String rootCause) {
        this.rootCause = rootCause;
    }

    public String getRootCauseType() {
        return rootCauseType;
    }

    public void setRootCauseType(String rootCauseType) {
        this.rootCauseType = rootCauseType;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getRecommendedAction() {
        return recommendedAction;
    }

    public void setRecommendedAction(String recommendedAction) {
        this.recommendedAction = recommendedAction;
    }

    public String getSuggestedFix() {
        return suggestedFix;
    }

    public void setSuggestedFix(String suggestedFix) {
        this.suggestedFix = suggestedFix;
    }

    public String getSimilarPatterns() {
        return similarPatterns;
    }

    public void setSimilarPatterns(String similarPatterns) {
        this.similarPatterns = similarPatterns;
    }

    public String getScreenshotObservation() {
        return screenshotObservation;
    }

    public void setScreenshotObservation(String screenshotObservation) {
        this.screenshotObservation = screenshotObservation;
    }

    public String getScreenshotHash() {
        return screenshotHash;
    }

    public void setScreenshotHash(String screenshotHash) {
        this.screenshotHash = screenshotHash;
    }

    public String getEvidenceJson() {
        return evidenceJson;
    }

    public void setEvidenceJson(String evidenceJson) {
        this.evidenceJson = evidenceJson;
    }

    public String getMissingEvidenceJson() {
        return missingEvidenceJson;
    }

    public void setMissingEvidenceJson(String missingEvidenceJson) {
        this.missingEvidenceJson = missingEvidenceJson;
    }

    public String getPreventionTipsJson() {
        return preventionTipsJson;
    }

    public void setPreventionTipsJson(String preventionTipsJson) {
        this.preventionTipsJson = preventionTipsJson;
    }

    public String getStepsToReproduceJson() {
        return stepsToReproduceJson;
    }

    public void setStepsToReproduceJson(String stepsToReproduceJson) {
        this.stepsToReproduceJson = stepsToReproduceJson;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Integer getConfidence() {
        return confidence;
    }

    public void setConfidence(Integer confidence) {
        this.confidence = confidence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

