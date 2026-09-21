package bug_investigation_agent.model.request;

/**
 * Request body for {@code PUT /api/failures/{failureId}/classification}.
 */
public class ClassificationUpdateRequest {

    private String humanClassification;

    public String getHumanClassification() {
        return humanClassification;
    }

    public void setHumanClassification(String humanClassification) {
        this.humanClassification = humanClassification;
    }
}

