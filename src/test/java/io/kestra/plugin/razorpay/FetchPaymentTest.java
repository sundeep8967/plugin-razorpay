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
import java.util.Base64;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@KestraTest
class FetchPaymentTest {

    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();
    private static final String TEST_KEY_ID = "rzp_test_fetchKey123";
    private static final String TEST_KEY_SECRET = "fetchSecretPass789";

    @Inject
    private RunContextFactory runContextFactory;

    private HttpServer server;
    private String baseUrl;

    private final AtomicReference<String> lastRequestMethod = new AtomicReference<>();
    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
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
    @DisplayName("FetchPayment executes successfully and populates all strongly-typed outputs")
    void shouldFetchPaymentSuccessfullyWhenValidPaymentId() throws Exception {
        String paymentJson = """
            {
              "id": "pay_29QQoUBi66xm2f",
              "amount": 50000,
              "currency": "INR",
              "status": "captured",
              "method": "upi",
              "order_id": "order_EKZJnqaLFfW3Dl",
              "email": "customer@example.com",
              "contact": "+919876543210",
              "fee": 1180,
              "tax": 180
            }
            """;
        responseBody.set(paymentJson);

        FetchPayment task = FetchPayment.builder()
            .id("fetch_payment_task")
            .type(FetchPayment.class.getName())
            .baseUrl(Property.ofValue(baseUrl))
            .keyId(Property.ofValue(TEST_KEY_ID))
            .keySecret(Property.ofValue(TEST_KEY_SECRET))
            .paymentId(Property.ofValue("pay_29QQoUBi66xm2f"))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Collections.emptyMap());
        FetchPayment.Output output = task.run(runContext);

        assertThat(lastRequestMethod.get()).isEqualTo("GET");
        assertThat(lastRequestPath.get()).isEqualTo("/payments/pay_29QQoUBi66xm2f");

        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString(
            (TEST_KEY_ID + ":" + TEST_KEY_SECRET).getBytes(StandardCharsets.UTF_8)
        );
        assertThat(lastAuthHeader.get()).isEqualTo(expectedAuth);

        assertThat(output).isNotNull();
        assertThat(output.getId()).isEqualTo("pay_29QQoUBi66xm2f");
        assertThat(output.getAmount()).isEqualTo(50000L);
        assertThat(output.getCurrency()).isEqualTo("INR");
        assertThat(output.getStatus()).isEqualTo("captured");
        assertThat(output.getMethod()).isEqualTo("upi");
        assertThat(output.getOrderId()).isEqualTo("order_EKZJnqaLFfW3Dl");
        assertThat(output.getEmail()).isEqualTo("customer@example.com");
        assertThat(output.getContact()).isEqualTo("+919876543210");
        assertThat(output.getFee()).isEqualTo(1180L);
        assertThat(output.getTax()).isEqualTo(180L);
        assertThat(output.getRawResponse()).isNotNull();
        assertThat(output.getRawResponse().get("id")).isEqualTo("pay_29QQoUBi66xm2f");
    }

    @Test
    @DisplayName("FetchPayment throws KestraRuntimeException on HTTP 400 Bad Request with error details")
    void shouldThrowKestraRuntimeExceptionWhenHttp400BadRequest() {
        responseStatusCode.set(400);
        responseBody.set("""
            {
              "error": {
                "code": "BAD_REQUEST_ERROR",
                "description": "Invalid payment ID format provided."
              }
            }
            """);

        FetchPayment task = FetchPayment.builder()
            .id("fetch_payment_bad_request")
            .type(FetchPayment.class.getName())
            .baseUrl(Property.ofValue(baseUrl))
            .keyId(Property.ofValue(TEST_KEY_ID))
            .keySecret(Property.ofValue(TEST_KEY_SECRET))
            .paymentId(Property.ofValue("invalid_id"))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Collections.emptyMap());

        assertThatThrownBy(() -> task.run(runContext))
            .isInstanceOf(KestraRuntimeException.class)
            .hasMessageContaining("HTTP 400")
            .hasMessageContaining("BAD_REQUEST_ERROR")
            .hasMessageContaining("Invalid payment ID format provided.");
    }

    @Test
    @DisplayName("FetchPayment throws KestraRuntimeException on HTTP 401 Unauthorized")
    void shouldThrowKestraRuntimeExceptionWhenHttp401Unauthorized() {
        responseStatusCode.set(401);
        responseBody.set("""
            {
              "error": {
                "code": "UNAUTHORIZED_ERROR",
                "description": "Authentication credentials are invalid."
              }
            }
            """);

        FetchPayment task = FetchPayment.builder()
            .id("fetch_payment_unauthorized")
            .type(FetchPayment.class.getName())
            .baseUrl(Property.ofValue(baseUrl))
            .keyId(Property.ofValue("wrong_key"))
            .keySecret(Property.ofValue("wrong_secret"))
            .paymentId(Property.ofValue("pay_29QQoUBi66xm2f"))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Collections.emptyMap());

        assertThatThrownBy(() -> task.run(runContext))
            .isInstanceOf(KestraRuntimeException.class)
            .hasMessageContaining("HTTP 401")
            .hasMessageContaining("UNAUTHORIZED_ERROR")
            .hasMessageContaining("Authentication credentials are invalid.");
    }

    @Test
    @DisplayName("FetchPayment throws KestraRuntimeException on HTTP 404 Not Found")
    void shouldThrowKestraRuntimeExceptionWhenHttp404NotFound() {
        responseStatusCode.set(404);
        responseBody.set("""
            {
              "error": {
                "code": "NOT_FOUND_ERROR",
                "description": "Payment pay_nonexistent does not exist."
              }
            }
            """);

        FetchPayment task = FetchPayment.builder()
            .id("fetch_payment_not_found")
            .type(FetchPayment.class.getName())
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
            .hasMessageContaining("Payment pay_nonexistent does not exist.");
    }

    @Test
    @DisplayName("FetchPayment throws KestraRuntimeException on HTTP 500 Internal Server Error")
    void shouldThrowKestraRuntimeExceptionWhenHttp500InternalServerError() {
        responseStatusCode.set(500);
        responseBody.set("""
            {
              "error": {
                "code": "SERVER_ERROR",
                "description": "An unexpected server error occurred."
              }
            }
            """);

        FetchPayment task = FetchPayment.builder()
            .id("fetch_payment_server_error")
            .type(FetchPayment.class.getName())
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
            .hasMessageContaining("An unexpected server error occurred.");
    }

    @Test
    @DisplayName("FetchPayment throws HttpTimeoutException when request times out")
    void shouldThrowExceptionWhenRequestTimesOut() {
        delayMillis.set(600L);

        FetchPayment task = FetchPayment.builder()
            .id("fetch_payment_timeout")
            .type(FetchPayment.class.getName())
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
    @DisplayName("FetchPayment excludes keySecret from toString representation")
    void shouldMaskKeySecretInToString() {
        FetchPayment task = FetchPayment.builder()
            .keyId(Property.ofValue(TEST_KEY_ID))
            .keySecret(Property.ofValue("TOP_SECRET_CREDENTIAL_PAYMENT"))
            .paymentId(Property.ofValue("pay_123"))
            .build();

        assertThat(task.toString()).doesNotContain("TOP_SECRET_CREDENTIAL_PAYMENT");
    }
}
