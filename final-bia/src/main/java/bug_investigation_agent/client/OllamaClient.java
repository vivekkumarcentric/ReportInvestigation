package bug_investigation_agent.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Component
public class OllamaClient {
    /** Max width (px) a screenshot is downscaled to before being sent to the vision model.
     *  Large/full-resolution screenshots consume a huge share of the model's limited context
     *  window as image tokens, causing it to effectively "not see" the image and hallucinate
     *  that no screenshot was provided. Downscaling keeps token usage predictable. */
    private static final int MAX_IMAGE_WIDTH = 800;
    private static final float JPEG_QUALITY = 0.7f;

    private final RestClient restClient;
    private final String baseUrl;
    private final String model;
    private final String visionModel;

    public OllamaClient(
            @Value("${ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${ollama.model:qwen2.5:7b}") String model,
            @Value("${ollama.vision-model:llava:7b}") String visionModel) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.baseUrl = baseUrl;
        this.model = model;
        this.visionModel = visionModel;
    }

    public String generate(String prompt) {
        return generate(prompt, null);
    }

    /**
     * Generates a response from Ollama. When a base64 (optionally data-URI prefixed) image is
     * supplied, the request is routed to the vision-capable model and the (downscaled/compressed)
     * base64 image bytes are attached via the "images" field so the model can actually analyze
     * the screenshot, instead of only being told textually that an image exists.
     */
    public String generate(String prompt, String imageBase64) {
        return generateWithMetrics(prompt, imageBase64).response();
    }

    /**
     * Same behavior as {@link #generate(String, String)} (identical request payload/options -
     * nothing about the actual Ollama call is changed), but additionally returns performance
     * metrics (HTTP duration, model used, token counts if Ollama reports them) so callers can
     * measure and log where time is being spent, without altering any request parameters.
     */
    public OllamaGenerationResult generateWithMetrics(String prompt, String imageBase64) {
        String rawImage = stripDataUriPrefix(imageBase64);
        boolean hasImage = rawImage != null && !rawImage.isBlank();
        String modelToUse = hasImage ? visionModel : model;

        if (hasImage) {
            rawImage = downscaleImage(rawImage);
        }

        Map<String, Object> options = Map.of(
                "temperature", 0.1,
                "num_predict", 2000,
                "num_ctx", hasImage ? 6144 : 8192
        );

        Map<String, Object> request = hasImage
                ? Map.of(
                        "model", modelToUse,
                        "prompt", prompt,
                        "images", List.of(rawImage),
                        "stream", false,
                        "format", "json",
                        "options", options)
                : Map.of(
                        "model", modelToUse,
                        "prompt", prompt,
                        "stream", false,
                        "format", "json",
                        "options", options);

        GenerateHttpResult httpResult = doGenerateRaw(request, modelToUse);
        Map<?, ?> raw = httpResult.raw();
        String responseText = raw == null ? null : String.valueOf(raw.get("response"));
        Integer promptEvalCount = raw == null ? null : toInteger(raw.get("prompt_eval_count"));
        Integer evalCount = raw == null ? null : toInteger(raw.get("eval_count"));

        return new OllamaGenerationResult(
                responseText,
                modelToUse,
                hasImage,
                httpResult.durationMillis(),
                promptEvalCount,
                evalCount
        );
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    /**
     * Result of a single Ollama /api/generate call, carrying performance metrics alongside the
     * response text: which model actually served the request, whether it was the vision model,
     * how long the HTTP call took, and token counts reported by Ollama (prompt_eval_count /
     * eval_count) when available (these are null if Ollama's response doesn't include them).
     */
    public record OllamaGenerationResult(
            String response,
            String model,
            boolean vision,
            long httpDurationMillis,
            Integer promptEvalCount,
            Integer evalCount
    ) {
    }

    /** Internal holder pairing the raw Ollama response map with the measured HTTP call duration. */
    private record GenerateHttpResult(Map<?, ?> raw, long durationMillis) {
    }

    private String stripDataUriPrefix(String image) {
        if (image == null) return null;
        String trimmed = image.trim();
        if (trimmed.isEmpty()) return null;
        int commaIndex = trimmed.indexOf(',');
        if (trimmed.startsWith("data:") && commaIndex > 0) {
            return trimmed.substring(commaIndex + 1);
        }
        return trimmed;
    }

    /**
     * Downscales the image to at most {@link #MAX_IMAGE_WIDTH} px wide and re-encodes it as a
     * compressed JPEG. This keeps the vision model's image-token budget small and predictable so
     * it can actually process the whole screenshot instead of truncating/ignoring it. Falls back
     * to the original base64 if decoding fails for any reason (e.g. unsupported format).
     */
    private String downscaleImage(String base64Image) {
        try {
            byte[] originalBytes = Base64.getDecoder().decode(base64Image);
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(originalBytes));
            if (original == null) {
                return base64Image; // not a decodable raster image, send as-is
            }

            int width = original.getWidth();
            int height = original.getHeight();

            BufferedImage toEncode = original;
            if (width > MAX_IMAGE_WIDTH) {
                int newWidth = MAX_IMAGE_WIDTH;
                int newHeight = Math.max(1, Math.round(height * (newWidth / (float) width)));

                BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = resized.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(original, 0, 0, newWidth, newHeight, null);
                g.dispose();
                toEncode = resized;
            } else if (original.getType() != BufferedImage.TYPE_INT_RGB) {
                // Normalize to RGB (JPEG doesn't support alpha) even when no resize is needed.
                BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                g.drawImage(original, 0, 0, null);
                g.dispose();
                toEncode = rgb;
            }

            byte[] compressed = encodeJpeg(toEncode, JPEG_QUALITY);
            if (compressed == null || compressed.length == 0) {
                return base64Image;
            }
            return Base64.getEncoder().encodeToString(compressed);
        } catch (Exception e) {
            // If anything goes wrong, fall back to sending the original image untouched
            // rather than failing the whole investigation request.
            return base64Image;
        }
    }

    private byte[] encodeJpeg(BufferedImage image, float quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            ByteArrayOutputStream fallback = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", fallback);
            return fallback.toByteArray();
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(quality);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(image, null, null), params);
            }
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private GenerateHttpResult doGenerateRaw(Map<String, Object> request, String model) {
        long start = System.nanoTime();
        try {
            Map<?, ?> response = restClient.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(Map.class);
            long durationMillis = (System.nanoTime() - start) / 1_000_000;
            return new GenerateHttpResult(response, durationMillis);
        } catch (RestClientException e) {
            throw new OllamaException(
                    "Failed to connect to Ollama service at " + baseUrl + ". " +
                    "Make sure Ollama is running and the model '" + model + "' is loaded. " +
                    "Error: " + e.getMessage(), e);
        }
    }

    public static class OllamaException extends RuntimeException {
        public OllamaException(String message) {
            super(message);
        }

        public OllamaException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
