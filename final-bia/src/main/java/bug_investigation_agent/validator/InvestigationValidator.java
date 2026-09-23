package bug_investigation_agent.validator;

import bug_investigation_agent.model.response.InvestigationResponse;
import org.springframework.stereotype.Component;

@Component
public class InvestigationValidator {

    public InvestigationResponse validate(
            InvestigationResponse response) {

        if (response == null) {
            throw new IllegalArgumentException(
                    "AI investigation response cannot be null"
            );
        }

        validateClassification(response);

        validateRootCauseType(response);

        validateConfidence(response);

        validateSeverity(response);

        validateEvidence(response);

        return response;
    }

    private void validateClassification(
            InvestigationResponse response) {

        if (response.getClassification() == null ||
                response.getClassification().isBlank()) {

            response.setClassification("UNKNOWN");
        }
    }

    private void validateRootCauseType(
            InvestigationResponse response) {

        if (response.getRootCauseType() == null ||
                response.getRootCauseType().isBlank()) {

            response.setRootCauseType("UNKNOWN");
            return;
        }

        String type = response.getRootCauseType().toUpperCase();

        if (!type.equals("CONFIRMED")
                && !type.equals("PROBABLE")
                && !type.equals("POSSIBLE")) {

            response.setRootCauseType("POSSIBLE");
        } else {
            response.setRootCauseType(type);
        }
    }

    private void validateConfidence(
            InvestigationResponse response) {

        int confidence = response.getConfidence();

        if (confidence < 0) {
            response.setConfidence(0);
        }

        if (confidence > 100) {
            response.setConfidence(100);
        }

        /*
         * A PROBABLE or UNKNOWN root cause cannot have
         * extremely high confidence.
         */
        if (!"CONFIRMED".equals(response.getRootCauseType())
                && response.getConfidence() > 80) {

            response.setConfidence(80);
        }
    }

    private void validateSeverity(
            InvestigationResponse response) {

        if (response.getSeverity() == null ||
                response.getSeverity().isBlank()) {

            response.setSeverity("MEDIUM");
        }
    }

    private void validateEvidence(
            InvestigationResponse response) {

        /*
         * A PROBABLE root cause should normally have
         * missing evidence identified.
         */
        if ("PROBABLE".equals(response.getRootCauseType())
                && (response.getMissingEvidence() == null
                || response.getMissingEvidence().isEmpty())) {

            response.setMissingEvidence(
                    new java.util.ArrayList<>()
            );

            response.getMissingEvidence().add(
                    "Additional evidence is required to confirm the root cause"
            );
        }
    }
}