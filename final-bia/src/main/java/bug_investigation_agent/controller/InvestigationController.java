package bug_investigation_agent.controller;

import bug_investigation_agent.model.request.InvestigationRequest;
import bug_investigation_agent.model.response.InvestigationResponse;
import bug_investigation_agent.service.InvestigationService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/investigations")
public class InvestigationController {

    private final InvestigationService investigationService;

    public InvestigationController(
            InvestigationService investigationService) {
        this.investigationService = investigationService;
    }

    @PostMapping
    public InvestigationResponse investigate(
            @RequestBody InvestigationRequest request) {

        return investigationService.investigate(request);
    }
}