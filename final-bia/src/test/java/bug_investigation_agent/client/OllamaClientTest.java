package bug_investigation_agent.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void visionRequestUsesConfiguredNumPredict() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>();
        startServer(bodyRef);

        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        OllamaClient client = new OllamaClient(baseUrl, "text-model", "vision-model", 1000);

        String image = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR4nGNgYAAAAAMAASsJTYQAAAAASUVORK5CYII=";
        OllamaClient.OllamaGenerationResult result = client.generateWithMetrics("prompt", image);

        assertThat(result.vision()).isTrue();
        assertThat(result.model()).isEqualTo("vision-model");

        JsonNode request = new ObjectMapper().readTree(bodyRef.get());
        assertThat(request.get("model").asText()).isEqualTo("vision-model");
        assertThat(request.has("images")).isTrue();
        assertThat(request.get("images").isArray()).isTrue();
        assertThat(request.get("images").size()).isEqualTo(1);
        assertThat(request.has("think")).isTrue();
        assertThat(request.get("think").asBoolean()).isFalse();
        assertThat(request.get("options").get("num_predict").asInt()).isEqualTo(1000);
    }

    @Test
    void textOnlyRequestRetainsExistingPayloadBehavior() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>();
        startServer(bodyRef);

        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        OllamaClient client = new OllamaClient(baseUrl, "text-model", "vision-model", 1000);

        OllamaClient.OllamaGenerationResult result = client.generateWithMetrics("prompt", "");

        assertThat(result.vision()).isFalse();
        assertThat(result.model()).isEqualTo("text-model");

        JsonNode request = new ObjectMapper().readTree(bodyRef.get());
        assertThat(request.get("model").asText()).isEqualTo("text-model");
        assertThat(request.has("images")).isFalse();
        assertThat(request.has("format")).isTrue();
        assertThat(request.get("format").asText()).isEqualTo("json");
        assertThat(request.get("options").get("num_predict").asInt()).isEqualTo(400);
    }

    private void startServer(AtomicReference<String> bodyRef) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/generate", new CaptureHandler(bodyRef));
        server.start();
    }

    private static class CaptureHandler implements HttpHandler {
        private final AtomicReference<String> bodyRef;

        private CaptureHandler(AtomicReference<String> bodyRef) {
            this.bodyRef = bodyRef;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            bodyRef.set(readBody(exchange.getRequestBody()));
            String response = "{\"response\":\"{}\",\"prompt_eval_count\":10,\"eval_count\":5}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private String readBody(InputStream in) throws IOException {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
