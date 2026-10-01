package bug_investigation_agent;

import bug_investigation_agent.model.request.InvestigationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InvestigationRequestDeserializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void supportsLegacyStringErrorFormat() throws Exception {
        String json = "{" +
                "\"testName\":\"t1\"," +
                "\"error\":\"Element not found\"," +
                "\"stackTrace\":\"at x.y.Z\"" +
                "}";

        InvestigationRequest request = objectMapper.readValue(json, InvestigationRequest.class);

        assertThat(request.getError()).isEqualTo("Element not found");
        assertThat(request.getStackTrace()).isEqualTo("at x.y.Z");
    }

    @Test
    void supportsArrayErrorFormat() throws Exception {
        String json = "{" +
                "\"testName\":\"t1\"," +
                "\"error\":[\"line 1\",\"line 2\"]," +
                "\"stackTrace\":[\"at a.A\",\"at b.B\"]" +
                "}";

        InvestigationRequest request = objectMapper.readValue(json, InvestigationRequest.class);

        assertThat(request.getError()).isEqualTo("line 1\nline 2");
        assertThat(request.getStackTrace()).isEqualTo("at a.A\nat b.B");
    }

    @Test
    void supportsObjectErrorFormat() throws Exception {
        String json = "{" +
                "\"testName\":\"t1\"," +
                "\"error\":{\"message\":\"Assertion failed\",\"code\":500}," +
                "\"stackTrace\":{\"error\":\"at c.C\"}" +
                "}";

        InvestigationRequest request = objectMapper.readValue(json, InvestigationRequest.class);

        assertThat(request.getError()).isEqualTo("Assertion failed");
        assertThat(request.getStackTrace()).isEqualTo("at c.C");
    }
}
