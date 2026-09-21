package bug_investigation_agent.model.response;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class InvestigationResponse {

    private String classification;
    private String rootCause;
    private int confidence;
    private List<String> evidence;
    private List<String> missingEvidence;
    private String severity;
    private String recommendedAction;
    private String suggestedFix;
    private String rootCauseType;
    private List<String> stepsToReproduce;
    private List<String> preventionTips;
    private String similarPatterns;
    private String screenshotObservation;

    // ------------------------------------------------------------------------------------------
    // Human classification feedback (backward-compatible additions). `classification` above
    // remains the EFFECTIVE classification (human correction if present, else the AI's), so
    // existing consumers of this field are unaffected. `failureId` identifies the persisted
    // feedback record for this failure so the UI can call the classification-correction API.
    // ------------------------------------------------------------------------------------------
    private String failureId;
    private String aiClassification;
    private String humanClassification;
    private String source;

    public String getFailureId() { return failureId; }
    public void setFailureId(String failureId) { this.failureId = failureId; }

    public String getAiClassification() { return aiClassification; }
    public void setAiClassification(String aiClassification) { this.aiClassification = aiClassification; }

    public String getHumanClassification() { return humanClassification; }
    public void setHumanClassification(String humanClassification) { this.humanClassification = humanClassification; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getClassification() {
        return classification;
    }

    public void setClassification(String classification) {
        this.classification = classification;
    }

    public String getRootCause() {
        return rootCause;
    }

    public void setRootCause(String rootCause) {
        this.rootCause = rootCause;
    }

    public int getConfidence() {
        return confidence;
    }

    public void setConfidence(int confidence) {
        this.confidence = confidence;
    }

    public List<String> getEvidence() {
        return evidence;
    }

    public void setEvidence(List<String> evidence) {
        this.evidence = evidence;
    }

    public List<String> getMissingEvidence() {
        return missingEvidence;
    }

    public void setMissingEvidence(List<String> missingEvidence) {
        this.missingEvidence = missingEvidence;
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

    public String getRootCauseType() {
        return rootCauseType;
    }

    public void setRootCauseType(String rootCauseType) {
        this.rootCauseType = rootCauseType;
    }

    public List<String> getStepsToReproduce() {
        return stepsToReproduce;
    }

    public void setStepsToReproduce(List<String> stepsToReproduce) {
        this.stepsToReproduce = stepsToReproduce;
    }

    public List<String> getPreventionTips() {
        return preventionTips;
    }

    public void setPreventionTips(List<String> preventionTips) {
        this.preventionTips = preventionTips;
    }

    public String getSimilarPatterns() {
        return similarPatterns;
    }

    public void setSimilarPatterns(String similarPatterns) {
        this.similarPatterns = similarPatterns;
    }

    public String getScreenshotObservation() { return screenshotObservation; }
    public void setScreenshotObservation(String screenshotObservation) { this.screenshotObservation = screenshotObservation; }
}