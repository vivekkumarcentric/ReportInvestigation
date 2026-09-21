package bug_investigation_agent.controller;

import bug_investigation_agent.client.OllamaClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ollama")
public class OllamaController {

    private final OllamaClient ollamaClient;

    public OllamaController(OllamaClient ollamaClient) {
        this.ollamaClient = ollamaClient;
    }

    @PostMapping("/test")
    public String test(@RequestBody String prompt) {
        return ollamaClient.generate(prompt);
    }
}
