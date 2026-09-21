package bug_investigation_agent.controller;

import bug_investigation_agent.model.request.ClassificationUpdateRequest;
import bug_investigation_agent.model.response.FailureClassificationResponse;
import bug_investigation_agent.service.FailureFeedbackService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Human classification feedback endpoints. Lets a user correct the AI's classification for a
 * given failure ({@code PUT}) and retrieve the current classification state for a failure
 * ({@code GET}) - e.g. on page reload, so the UI can show a previously-saved correction.
 */
@RestController
@RequestMapping("/api/failures")
public class FailureFeedbackController {

    private final FailureFeedbackService failureFeedbackService;

    public FailureFeedbackController(FailureFeedbackService failureFeedbackService) {
        this.failureFeedbackService = failureFeedbackService;
    }

    @PutMapping("/{failureId}/classification")
    public FailureClassificationResponse updateClassification(
            @PathVariable String failureId,
            @RequestBody(required = false) ClassificationUpdateRequest request) {

        String humanClassification = request == null ? null : request.getHumanClassification();
        return failureFeedbackService.saveHumanClassification(failureId, humanClassification);
    }

    @GetMapping("/{failureId}/classification")
    public ResponseEntity<FailureClassificationResponse> getClassification(@PathVariable String failureId) {
        return failureFeedbackService.getClassification(failureId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

