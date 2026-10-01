package bug_investigation_agent.model.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Accepts scalar, array, or object JSON and produces a usable text value.
 */
public class LenientStringDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token == JsonToken.VALUE_STRING) {
            return p.getValueAsString();
        }

        JsonNode node = p.readValueAsTree();
        return flatten(node);
    }

    private String flatten(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode child : node) {
                String text = flatten(child);
                if (text != null && !text.isBlank()) {
                    parts.add(text);
                }
            }
            if (parts.isEmpty()) {
                return null;
            }
            return String.join("\n", parts);
        }

        JsonNode message = node.get("message");
        if (message != null && !message.isNull()) {
            String msg = flatten(message);
            if (msg != null && !msg.isBlank()) {
                return msg;
            }
        }

        JsonNode error = node.get("error");
        if (error != null && !error.isNull()) {
            String err = flatten(error);
            if (err != null && !err.isBlank()) {
                return err;
            }
        }

        // For unknown object shapes, preserve details as key=value lines.
        List<String> fields = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String value = flatten(entry.getValue());
            if (value != null && !value.isBlank()) {
                fields.add(entry.getKey() + ": " + value);
            }
        }
        if (!fields.isEmpty()) {
            return String.join("\n", fields);
        }

        return node.toString();
    }
}
