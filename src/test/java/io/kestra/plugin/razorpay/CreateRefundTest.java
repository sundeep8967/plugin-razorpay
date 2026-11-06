package io.kestra.plugin.razorpay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.kestra.core.exceptions.KestraRuntimeException;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.TestsUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@KestraTest
class CreateRefundTest {

    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();
    private static final String TEST_KEY_ID = "rzp_test_refundKey123";
    private static final String TEST_KEY_SECRET = "refundSecretPass789";

    @Inject
    private RunContextFactory runContextFactory;

    private HttpServer server;
    private String baseUrl;

    private final AtomicReference<String> lastRequestMethod = new AtomicReference<>();
    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicInteger responseStatusCode = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicLong delayMillis = new AtomicLong(0);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            lastRequestMethod.set(exchange.getRequestMethod());
            lastRequestPath.set(exchange.getRequestURI().getPath());
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));

            String requestBodyString = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastRequestBody.set(requestBodyString);

            long delay = delayMillis.get();
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }

            int code = responseStatusCode.get();
            String body = responseBody.get();
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;
        responseStatusCode.set(200);
        responseBody.set("");
        delayMillis.set(0L);
    }

    @AfterEach
    void tearDown() {
        if (Objects.nonNull(server)) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("CreateRefund executes successfully and populates all strongly-typed outputs")
    void shouldCreateRefundSuccessfullyWhenValidInput() throws Exception {
        String refundJson = """
            {
              "id": "rfnd_FP1234567890AB",
              "payment_id": "pay_29QQoUBi66xm2f",
              "amount": 25000,
              "currency": "INR",
              "status": "processed",
              "speed_processed": "normal",
              "receipt": "rcpt_ref_1001",
              "created_at": 1700000100
            }
            """;
        responseBody.set(refundJson);

        CreateRefund task = CreateRefund.builder()
            .id("create_refund_task")
            .type(CreateRefund.class.getName())
            .baseUrl(Property.ofValue(baseUrl))
            .keyId(Property.ofValue(TEST_KEY_ID))
            .keySecret(Property.ofValue(TEST_KEY_SECRET))
            .paymentId(Property.ofValue("pay_29QQoUBi66xm2f"))
            .amount(Property.ofValue(25000L))
            .speed(Property.ofValue("normal"))
            .receipt(Property.ofValue("rcpt_ref_1001"))
            .notes(Property.ofValue(Map.of("reason", "customer_request")))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Collections.emptyMap());
        CreateRefund.Output output = task.run(runContext);

        assertThat(lastRequestMethod.get()).isEqualTo("POST");
        assertThat(lastRequestPath.get()).isEqualTo("/payments/pay_29QQoUBi66xm2f/refund");

        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString(
            (TEST_KEY_ID + ":" + TEST_KEY_SECRET).getBytes(StandardCharsets.UTF_8)
        );
        assertThat(lastAuthHeader.get()).isEqualTo(expectedAuth);

        Map<String, Object> payload = MAPPER.readValue(lastRequestBody.get(), Map.class);
        assertThat(payload.get("amount")).isEqualTo(25000);
        assertThat(payload.get("speed")).isEqualTo("normal");
        assertThat(payload.get("receipt")).isEqualTo("rcpt_ref_1001");

        assertThat(output).isNotNull();
        assertThat(output.getId()).isEqualTo("rfnd_FP1234567890AB");
        assertThat(output.getPaymentId()).isEqualTo("pay_29QQoUBi66xm2f");
        assertThat(output.getAmount()).isEqualTo(25000L);
        assertThat(output.getCurrency()).isEqualTo("INR");
        assertThat(output.getStatus()).isEqualTo("processed");
        assertThat(output.getSpeedProcessed()).isEqualTo("normal");
        assertThat(output.getReceipt()).isEqualTo("rcpt_ref_1001");
        assertThat(output.getCreatedAt()).isEqualTo(Instant.ofEpochSecond(1700000100L));
        assertThat(output.getRawResponse()).isNotNull();
        assertThat(output.getRawResponse().get("id")).isEqualTo("rfnd_FP1234567890AB");
    }

    @Test
    @DisplayName("CreateRefund creates full refund when amount is omitted")
    void shouldCreateFullRefundWhenAmountOmitted() throws Exception {
        String refundJson = """
            {
              "id": "rfnd_FULL12345678",
              "payment_id": "pay_29QQoUBi66xm2f",
              "amount": 50000,
              "currency": "INR",
              "status": "processed",
              "speed_processed": "normal",
              "created_at": 1700000100
            }
            """;
        responseBody.set(refundJson);

        CreateRefund task = CreateRefund.builder()
            .id("create_refund_full")
            .type(CreateRefund.class.getName())
            .baseUrl(Property.ofValue(baseUrl))
            .keyId(Property.ofValue(TEST_KEY_ID))
            .keySecret(Property.ofValue(TEST_KEY_SECRET))
            .paymentId(Property.ofValue("pay_29QQoUBi66xm2f"))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Collections.emptyMap());
        CreateRefund.Output output = task.run(runContext);

        assertThat(output).isNotNull();
        assertThat(output.getId()).isEqualTo("rfnd_FULL12345678");
        assertThat(output.getAmount()).isEqualTo(50000L);

        Map<String, Object> payload = MAPPER.readValue(lastRequestBody.get(), Map.class);
        assertThat(payload.containsKey("amount")).isFalse();
        assertThat(payload.get("speed")).isEqualTo("normal");
    }

    @Test
    @DisplayName("CreateRefund throws KestraRuntimeException on HTTP 400 Bad Request with error details")
    void shouldThrowKestraRuntimeExceptionWhenHttp400BadRequest() {
        responseStatusCode.set(400);
        responseBody.set("""
            {
              "error": {
                "code": "BAD_REQUEST_ERROR",
                "description": "Refund amount is greater than payment amount."
              }
            }
            """);

        CreateRefund task = CreateRefund.builder()
            .id("create_refund_bad_request")
            .type(CreateRefund.class.getName())
            .baseUrl(Property.ofValue(baseUrl))
            .keyId(Property.ofValue(TEST_KEY_ID))
            .keySecret(Property.ofValue(TEST_KEY_SECRET))
            .paymentId(Property.ofValue("pay_29QQoUBi66xm2f"))
            .amount(Property.ofValue(999999L))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Collections.emptyMap());

        assertThatThrownBy(() -> task.run(runContext))
            .isInstanceOf(KestraRuntimeException.class)
            .hasMessageContaining("HTTP 400")
            .hasMessageContaining("BAD_REQUEST_ERROR")
            .hasMessageContaining("Refund amount is greater than payment amount.");
    }

    @Test
    @DisplayName("CreateRefund throws KestraRuntimeException on HTTP 401 Unauthorized")
    void shouldThrowKestraRuntimeExceptionWhenHttp401Unauthorized() {
        responseStatusCode.set(401);
        responseBody.set("""
            {
              "error": {
                "code": "UNAUTHORIZED_ERROR",
                "description": "The API credentials are unauthorized."
              }
            }
            """);

        CreateRefund task = CreateRefund.builder()
            .id("create_refund_unauthorized")
            .type(CreateRefund.class.getName())
            .baseUrl(Property.ofValue(baseUrl))
            .keyId(Property.ofValue("invalid_key"))
            .keySecret(Property.ofValue("invalid_secret"))
            .paymentId(Property.ofValue("pay_29QQoUBi66xm2f"))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Collections.emptyMap());

        assertThatThrownBy(() -> task.run(runContext))
            .isInstanceOf(KestraRuntimeException.class)
            .hasMessageContaining("HTTP 401")
            .hasMessageContaining("UNAUTHORIZED_ERROR")
            .hasMessageContaining("The API credentials are unauthorized.");
    }

    @Test
    @DisplayName("CreateRefund throws KestraRuntimeException on HTTP 404 Not Found")
    void shouldThrowKestraRuntimeExceptionWhenHttp404NotFound() {
        responseStatusCode.set(404);
        responseBody.set("""
            {
              "error": {
                "code": "NOT_FOUND_ERROR",
                "description": "Payment not found for refund."
              }
            }
            """);

        CreateRefund task = CreateRefund.builder()
            .id("create_refund_not_found")
            .type(CreateRefund.class.getName())
            .baseUrl(Property.ofValue(baseUrl))
            .keyId(Property.ofValue(TEST_KEY_ID))
            .keySecret(Property.ofValue(TEST_KEY_SECRET))
            .paymentId(Property.ofValue("pay_nonexistent"))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Collections.emptyMap());

        assertThatThrownBy(() -> task.run(runContext))
            .isInstanceOf(KestraRuntimeException.class)
            .hasMessageContaining("HTTP 404")
            .hasMessageContaining("NOT_FOUND_ERROR")
            .hasMessageContaining("Payment not found for refund.");
    }

    @Test
    @DisplayName("CreateRefund throws KestraRuntimeException on HTTP 500 Internal Server Error")
    void shouldThrowKestraRuntimeExceptionWhenHttp500InternalServerError() {
        responseStatusCode.set(500);
        responseBody.set("""
            {
              "error": {
                "code": "SERVER_ERROR",
                "description": "An unexpected server error occurred during refund processing."
              }
            }
            """);

        CreateRefund task = CreateRefund.builder()
            .id("create_refund_server_error")
            .type(CreateRefund.class.getName())
            .baseUrl(Property.ofValue(baseUrl))
            .keyId(Property.ofValue(TEST_KEY_ID))
            .keySecret(Property.ofValue(TEST_KEY_SECRET))
            .paymentId(Property.ofValue("pay_29QQoUBi66xm2f"))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Collections.emptyMap());

        assertThatThrownBy(() -> task.run(runContext))
            .isInstanceOf(KestraRuntimeException.class)
            .hasMessageContaining("HTTP 500")
            .hasMessageContaining("SERVER_ERROR")
            .hasMessageContaining("An unexpected server error occurred during refund processing.");
    }

    @Test
    @DisplayName("CreateRefund throws HttpTimeoutException when request times out")
    void shouldThrowExceptionWhenRequestTimesOut() {
        delayMillis.set(600L);

        CreateRefund task = CreateRefund.builder()
            .id("create_refund_timeout")
            .type(CreateRefund.class.getName())
            .baseUrl(Property.ofValue(baseUrl))
            .keyId(Property.ofValue(TEST_KEY_ID))
            .keySecret(Property.ofValue(TEST_KEY_SECRET))
            .paymentId(Property.ofValue("pay_29QQoUBi66xm2f"))
            .timeout(Property.ofValue(Duration.ofMillis(100)))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Collections.emptyMap());

        assertThatThrownBy(() -> task.run(runContext))
            .isInstanceOf(HttpTimeoutException.class);
    }

    @Test
    @DisplayName("CreateRefund excludes keySecret from toString representation")
    void shouldMaskKeySecretInToString() {
        CreateRefund task = CreateRefund.builder()
            .keyId(Property.ofValue(TEST_KEY_ID))
            .keySecret(Property.ofValue("TOP_SECRET_CREDENTIAL_REFUND"))
            .paymentId(Property.ofValue("pay_123"))
            .build();

        assertThat(task.toString()).doesNotContain("TOP_SECRET_CREDENTIAL_REFUND");
    }
}
