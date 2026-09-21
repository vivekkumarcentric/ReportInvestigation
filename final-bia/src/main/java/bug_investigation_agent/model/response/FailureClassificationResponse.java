package bug_investigation_agent.model.response;

/**
 * Response for the human-classification-feedback endpoints
 * ({@code PUT}/{@code GET} {@code /api/failures/{failureId}/classification}).
 *
 * <p>{@code source} is one of:</p>
 * <ul>
 *   <li>{@code AI} - no human correction exists; {@code effectiveClassification} is the AI's
 *       classification.</li>
 *   <li>{@code HUMAN_CORRECTED} - a human has corrected the classification;
 *       {@code effectiveClassification} is the human's classification.</li>
 *   <li>{@code HISTORICAL} - reserved for a future feature (similarity-based historical lookup);
 *       not produced by this phase of the feature.</li>
 * </ul>
 */
public class FailureClassificationResponse {

    private String failureId;
    private String aiClassification;
    private String humanClassification;
    private String effectiveClassification;
    private String source;

    public FailureClassificationResponse() {
    }

    public FailureClassificationResponse(
            String failureId,
            String aiClassification,
            String humanClassification,
            String effectiveClassification,
            String source) {
        this.failureId = failureId;
        this.aiClassification = aiClassification;
        this.humanClassification = humanClassification;
        this.effectiveClassification = effectiveClassification;
        this.source = source;
    }

    public String getFailureId() {
        return failureId;
    }

    public void setFailureId(String failureId) {
        this.failureId = failureId;
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

    public String getEffectiveClassification() {
        return effectiveClassification;
    }

    public void setEffectiveClassification(String effectiveClassification) {
        this.effectiveClassification = effectiveClassification;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}

