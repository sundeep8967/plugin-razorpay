package io.kestra.plugin.razorpay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.exceptions.KestraRuntimeException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractRazorpayTask extends Task {
    protected static final ObjectMapper MAPPER = JacksonMapper.ofJson();
    protected static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE = new TypeReference<>() {};

    @NotNull
    @Schema(
        title = "Key ID",
        description = "The Razorpay API Key ID."
    )
    @PluginProperty(group = "connection")
    protected Property<String> keyId;

    @NotNull
    @Schema(
        title = "Key Secret",
        description = "The Razorpay API Key Secret."
    )
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    protected Property<String> keySecret;

    @Schema(
        title = "Base URL",
        description = "The Razorpay API base endpoint."
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    protected Property<String> baseUrl = Property.ofValue("https://api.razorpay.com/v1");

    @Schema(
        title = "HTTP Request Timeout",
        description = "The timeout for HTTP requests."
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    protected Property<Duration> timeout = Property.ofValue(Duration.ofSeconds(15));

    public String resolveBasicAuth(String keyId, String keySecret) {
        if (keyId == null || keySecret == null) {
            throw new IllegalArgumentException("Key ID and Key Secret must not be null.");
        }
        String credentials = keyId + ":" + keySecret;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    protected String resolveBasicAuth(RunContext runContext) throws IllegalVariableEvaluationException {
        String renderedKeyId = runContext.render(this.keyId).as(String.class).orElseThrow();
        String renderedKeySecret = runContext.render(this.keySecret).as(String.class).orElseThrow();
        return resolveBasicAuth(renderedKeyId, renderedKeySecret);
    }

    protected String resolveEndpoint(String base, String path) {
        String sanitizedBase = base.replaceAll("/+$", "");
        String sanitizedPath = path.startsWith("/") ? path : "/" + path;
        return sanitizedBase + sanitizedPath;
    }

    protected HttpRequest.Builder newRequestBuilder(RunContext runContext, String path) throws IllegalVariableEvaluationException {
        String renderedKeyId = runContext.render(this.keyId).as(String.class).orElseThrow();
        String renderedKeySecret = runContext.render(this.keySecret).as(String.class).orElseThrow();
        String renderedBaseUrl = runContext.render(this.baseUrl).as(String.class).orElse("https://api.razorpay.com/v1");
        Duration renderedTimeout = runContext.render(this.timeout).as(Duration.class).orElse(Duration.ofSeconds(15));

        String endpoint = resolveEndpoint(renderedBaseUrl, path);

        return HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(renderedTimeout)
            .header("Authorization", resolveBasicAuth(renderedKeyId, renderedKeySecret))
            .header("Accept", "application/json");
    }

    protected String executeRequest(HttpRequest request, HttpClient client) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            handleErrorResponse(statusCode, response.body());
        }
        return response.body();
    }

    protected String executeRequest(HttpRequest request) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return executeRequest(request, client);
        }
    }

    protected String executeRequest(RunContext runContext, HttpRequest request) throws IOException, InterruptedException {
        return executeRequest(request);
    }

    protected Map<String, Object> executeRequestAsMap(HttpRequest request, HttpClient client) throws IOException, InterruptedException {
        String responseBody = executeRequest(request, client);
        return MAPPER.readValue(responseBody, MAP_TYPE_REFERENCE);
    }

    protected Map<String, Object> executeRequestAsMap(HttpRequest request) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return executeRequestAsMap(request, client);
        }
    }

    protected void handleErrorResponse(int statusCode, String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new KestraRuntimeException(
                String.format("Razorpay API request failed with HTTP %d.", statusCode)
            );
        }

        try {
            JsonNode rootNode = MAPPER.readTree(responseBody);
            if (Objects.nonNull(rootNode) && rootNode.has("error")) {
                JsonNode errorNode = rootNode.get("error");
                if (errorNode.isObject()) {
                    String code = errorNode.hasNonNull("code") ? errorNode.get("code").asText() : "UNKNOWN";
                    String description = errorNode.hasNonNull("description") ? errorNode.get("description").asText() : "";
                    if (description.isBlank() == false) {
                        throw new KestraRuntimeException(
                            String.format("Razorpay API request failed with HTTP %d [%s]: %s.", statusCode, code, description)
                        );
                    }
                    throw new KestraRuntimeException(
                        String.format("Razorpay API request failed with HTTP %d [%s].", statusCode, code)
                    );
                } else if (errorNode.isValueNode()) {
                    throw new KestraRuntimeException(
                        String.format("Razorpay API request failed with HTTP %d: %s.", statusCode, errorNode.asText())
                    );
                }
            }
        } catch (KestraRuntimeException e) {
            throw e;
        } catch (Exception ignored) {
            // Fall back when response body is not JSON or lacks standard error structure
        }

        throw new KestraRuntimeException(
            String.format("Razorpay API request failed with HTTP %d: %s.", statusCode, responseBody)
        );
    }

    public static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str) {
            try {
                return Long.parseLong(str);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static Boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str);
        }
        return null;
    }

    public static Instant toInstant(Object value) {
        if (value instanceof Number number) {
            return Instant.ofEpochSecond(number.longValue());
        }
        if (value instanceof String str) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(str));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static String toStringVal(Object value) {
        if (Objects.isNull(value)) {
            return null;
        }
        return value.toString();
    }
}
