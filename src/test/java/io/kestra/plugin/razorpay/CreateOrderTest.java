package io.kestra.plugin.razorpay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.kestra.core.exceptions.KestraRuntimeException;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.annotations.PluginSubGroup;
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
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
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
class CreateOrderTest {

    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();
    private static final String TEST_KEY_ID = "rzp_test_orderKey123";
    private static final String TEST_KEY_SECRET = "orderSecretPass789";

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
    @DisplayName("CreateOrder executes successfully and populates all strongly-typed outputs")
    void shouldCreateOrderSuccessfullyWhenValidInput() throws Exception {
        String successJson = """
            {
              "id": "order_EKZJnqaLFfW3Dl",
              "entity": "order",
              "amount": 50000,
              "amount_paid": 0,
              "amount_due": 50000,
              "currency": "INR",
              "receipt": "rcpt_order_1001",
              "status": "created",
              "created_at": 1700000000
            }
            """;
        responseBody.set(successJson);

        CreateOrder task = CreateOrder.builder()
            .id("create_order_task")
            .type(CreateOrder.class.getName())
            .baseUrl(Property.ofValue(baseUrl))
            .keyId(Property.ofValue(TEST_KEY_ID))
            .keySecret(Property.ofValue(TEST_KEY_SECRET))
            .amount(Property.ofValue(50000L))
            .currency(Property.ofValue("INR"))
            .receipt(Property.ofValue("rcpt_order_1001"))
            .notes(Property.ofValue(Map.of("department", "finance")))
            .partialPayment(Property.ofValue(Boolean.TRUE))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Collections.emptyMap());
        CreateOrder.Output output = task.run(runContext);

        assertThat(lastRequestMethod.get()).isEqualTo("POST");
        assertThat(lastRequestPath.get()).isEqualTo("/orders");

        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString(
            (TEST_KEY_ID + ":" + TEST_KEY_SECRET).getBytes(StandardCharsets.UTF_8)
        );
        assertThat(lastAuthHeader.get()).isEqualTo(expectedAuth);

        Map<String, Object> payload = MAPPER.readValue(lastRequestBody.get(), Map.class);
        assertThat(payload.get("amount")).isEqualTo(50000);
        assertThat(payload.get("currency")).isEqualTo("INR");
        assertThat(payload.get("receipt")).isEqualTo("rcpt_order_1001");
        assertThat(payload.get("partial_payment")).isEqualTo(Boolean.TRUE);

        assertThat(output).isNotNull();
        assertThat(output.getId()).isEqualTo("order_EKZJnqaLFfW3Dl");
        assertThat(output.getEntity()).isEqualTo("order");
        assertThat(output.getAmount()).isEqualTo(50000L);
        assertThat(output.getAmountPaid()).isEqualTo(0L);
        assertThat(output.getAmountDue()).isEqualTo(50000L);
        assertThat(output.getCurrency()).isEqualTo("INR");
        assertThat(output.getReceipt()).isEqualTo("rcpt_order_1001");
        assertThat(output.getStatus()).isEqualTo("created");
        assertThat(output.getCreatedAt()).isEqualTo(Instant.ofEpochSecond(1700000000L));
        assertThat(output.getRawResponse()).isNotNull();
        assertThat(output.getRawResponse().get("id")).isEqualTo("order_EKZJnqaLFfW3Dl");
    }

    @Test
    @DisplayName("CreateOrder defaults currency to INR and omits optional parameters when not provided")
    void shouldCreateOrderWithDefaultCurrencyAndOmittedOptionals() throws Exception {
        String successJson = """
            {
              "id": "order_DEF987654321",
              "entity": "order",
              "amount": 25000,
              "amount_paid": 0,
              "amount_due": 25000,
              "currency": "INR",
              "status": "created",
              "created_at": 1700000000
            }
            """;
        responseBody.set(successJson);

        CreateOrder task = CreateOrder.builder()
            .id("create_order_minimal")
            .type(CreateOrder.class.getName())
            .baseUrl(Property.ofValue(baseUrl))
            .keyId(Property.ofValue(TEST_KEY_ID))
            .keySecret(Property.ofValue(TEST_KEY_SECRET))
            .amount(Property.ofValue(25000L))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Collections.emptyMap());
        CreateOrder.Output output = task.run(runContext);

        assertThat(output).isNotNull();
        assertThat(output.getId()).isEqualTo("order_DEF987654321");
        assertThat(output.getAmount()).isEqualTo(25000L);
        assertThat(output.getCurrency()).isEqualTo("INR");

        Map<String, Object> payload = MAPPER.readValue(lastRequestBody.get(), Map.class);
        assertThat(payload.get("amount")).isEqualTo(25000);
        assertThat(payload.get("currency")).isEqualTo("INR");
        assertThat(payload.containsKey("receipt")).isFalse();
        assertThat(payload.containsKey("notes")).isFalse();
        assertThat(payload.containsKey("partial_payment")).isFalse();
    }

    @Test
    @DisplayName("CreateOrder throws KestraRuntimeException on HTTP 400 Bad Request with error details")
    void shouldThrowKestraRuntimeExceptionWhenHttp400BadRequest() {
        responseStatusCode.set(400);
        responseBody.set("""
            {
              "error": {
                "code": "BAD_REQUEST_ERROR",
                "description": "Order amount is less than minimum amount allowed"
              }
            }
            """);

        CreateOrder task = CreateOrder.builder()
            .id("create_order_bad_request")
            .type(CreateOrder.class.getName())
            .baseUrl(Property.ofValue(baseUrl))
            .keyId(Property.ofValue(TEST_KEY_ID))
            .keySecret(Property.ofValue(TEST_KEY_SECRET))
            .amount(Property.ofValue(10L))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Collections.emptyMap());

        assertThatThrownBy(() -> task.run(runContext))
            .isInstanceOf(KestraRuntimeException.class)
            .hasMessageContaining("HTTP 400")
            .hasMessageContaining("BAD_REQUEST_ERROR")
            .hasMessageContaining("Order amount is less than minimum amount allowed");
    }

    @Test
    @DisplayName("CreateOrder throws KestraRuntimeException on HTTP 401 Unauthorized")
    void shouldThrowKestraRuntimeExceptionWhenHttp401Unauthorized() {
        responseStatusCode.set(401);
        responseBody.set("""
            {
              "error": {
                "code": "UNAUTHORIZED_ERROR",
                "description": "The key or secret provided is invalid."
              }
            }
            """);

        CreateOrder task = CreateOrder.builder()
            .id("create_order_unauthorized")
            .type(CreateOrder.class.getName())
            .baseUrl(Property.ofValue(baseUrl))
            .keyId(Property.ofValue("invalid_key"))
            .keySecret(Property.ofValue("invalid_secret"))
            .amount(Property.ofValue(50000L))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Collections.emptyMap());

        assertThatThrownBy(() -> task.run(runContext))
            .isInstanceOf(KestraRuntimeException.class)
            .hasMessageContaining("HTTP 401")
            .hasMessageContaining("UNAUTHORIZED_ERROR")
            .hasMessageContaining("The key or secret provided is invalid.");
    }

    @Test
    @DisplayName("CreateOrder throws KestraRuntimeException on HTTP 404 Not Found")
    void shouldThrowKestraRuntimeExceptionWhenHttp404NotFound() {
        responseStatusCode.set(404);
        responseBody.set("""
            {
              "error": {
                "code": "NOT_FOUND_ERROR",
                "description": "The requested endpoint does not exist."
              }
            }
            """);

        CreateOrder task = CreateOrder.builder()
            .id("create_order_not_found")
            .type(CreateOrder.class.getName())
            .baseUrl(Property.ofValue(baseUrl))
            .keyId(Property.ofValue(TEST_KEY_ID))
            .keySecret(Property.ofValue(TEST_KEY_SECRET))
            .amount(Property.ofValue(50000L))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Collections.emptyMap());

        assertThatThrownBy(() -> task.run(runContext))
            .isInstanceOf(KestraRuntimeException.class)
            .hasMessageContaining("HTTP 404")
            .hasMessageContaining("NOT_FOUND_ERROR")
            .hasMessageContaining("The requested endpoint does not exist.");
    }

    @Test
    @DisplayName("CreateOrder throws KestraRuntimeException on HTTP 500 Internal Server Error")
    void shouldThrowKestraRuntimeExceptionWhenHttp500InternalServerError() {
        responseStatusCode.set(500);
        responseBody.set("""
            {
              "error": {
                "code": "SERVER_ERROR",
                "description": "Internal server error occurred."
              }
            }
            """);

        CreateOrder task = CreateOrder.builder()
            .id("create_order_server_error")
            .type(CreateOrder.class.getName())
            .baseUrl(Property.ofValue(baseUrl))
            .keyId(Property.ofValue(TEST_KEY_ID))
            .keySecret(Property.ofValue(TEST_KEY_SECRET))
            .amount(Property.ofValue(50000L))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Collections.emptyMap());

        assertThatThrownBy(() -> task.run(runContext))
            .isInstanceOf(KestraRuntimeException.class)
            .hasMessageContaining("HTTP 500")
            .hasMessageContaining("SERVER_ERROR")
            .hasMessageContaining("Internal server error occurred.");
    }

    @Test
    @DisplayName("CreateOrder throws HttpTimeoutException when request times out")
    void shouldThrowExceptionWhenRequestTimesOut() {
        delayMillis.set(600L);

        CreateOrder task = CreateOrder.builder()
            .id("create_order_timeout")
            .type(CreateOrder.class.getName())
            .baseUrl(Property.ofValue(baseUrl))
            .keyId(Property.ofValue(TEST_KEY_ID))
            .keySecret(Property.ofValue(TEST_KEY_SECRET))
            .amount(Property.ofValue(50000L))
            .timeout(Property.ofValue(Duration.ofMillis(100)))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Collections.emptyMap());

        assertThatThrownBy(() -> task.run(runContext))
            .isInstanceOf(HttpTimeoutException.class);
    }

    @Test
    @DisplayName("CreateOrder excludes keySecret from toString representation")
    void shouldMaskKeySecretInToString() {
        CreateOrder task = CreateOrder.builder()
            .keyId(Property.ofValue(TEST_KEY_ID))
            .keySecret(Property.ofValue("CONFIDENTIAL_SECRET_DO_NOT_REVEAL"))
            .amount(Property.ofValue(50000L))
            .build();

        assertThat(task.toString()).doesNotContain("CONFIDENTIAL_SECRET_DO_NOT_REVEAL");
    }

    @Test
    @DisplayName("CreateOrder resolves HTTP Basic Auth credentials into valid header")
    void shouldResolveBasicAuthCredentialsCorrectly() {
        CreateOrder task = CreateOrder.builder().build();
        String authHeader = task.resolveBasicAuth("sampleKey", "sampleSecret");
        String expected = "Basic " + Base64.getEncoder().encodeToString("sampleKey:sampleSecret".getBytes(StandardCharsets.UTF_8));
        assertThat(authHeader).isEqualTo(expected);
    }

    @Test
    @DisplayName("CreateOrder converts numeric and date helper types accurately")
    void shouldConvertNumericAndDateHelpersCorrectly() {
        assertThat(AbstractRazorpayTask.toLong(50000)).isEqualTo(50000L);
        assertThat(AbstractRazorpayTask.toLong("50000")).isEqualTo(50000L);
        assertThat(AbstractRazorpayTask.toLong(null)).isNull();

        assertThat(AbstractRazorpayTask.toInteger(100)).isEqualTo(100);
        assertThat(AbstractRazorpayTask.toInteger("100")).isEqualTo(100);
        assertThat(AbstractRazorpayTask.toInteger(null)).isNull();

        assertThat(AbstractRazorpayTask.toBoolean(Boolean.TRUE)).isTrue();
        assertThat(AbstractRazorpayTask.toBoolean("true")).isTrue();
        assertThat(AbstractRazorpayTask.toBoolean(null)).isNull();

        assertThat(AbstractRazorpayTask.toInstant(1700000000L)).isEqualTo(Instant.ofEpochSecond(1700000000L));
        assertThat(AbstractRazorpayTask.toInstant(null)).isNull();

        assertThat(AbstractRazorpayTask.toStringVal("order_123")).isEqualTo("order_123");
        assertThat(AbstractRazorpayTask.toStringVal(null)).isNull();
    }

    @Test
    @DisplayName("CreateOrder resolves endpoint paths cleanly")
    void shouldResolveEndpointsCorrectly() {
        CreateOrder task = CreateOrder.builder().build();
        assertThat(task.resolveEndpoint("https://api.razorpay.com/v1", "/orders"))
            .isEqualTo("https://api.razorpay.com/v1/orders");
        assertThat(task.resolveEndpoint("https://api.razorpay.com/v1/", "orders"))
            .isEqualTo("https://api.razorpay.com/v1/orders");
    }

    @Test
    @DisplayName("CreateOrder throws IllegalArgumentException when credentials are null")
    void shouldThrowExceptionWhenCredentialsAreNull() {
        CreateOrder task = CreateOrder.builder().build();
        assertThatThrownBy(() -> task.resolveBasicAuth(null, "secret"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> task.resolveBasicAuth("key", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Verify keySecret field annotations in AbstractRazorpayTask")
    void shouldVerifyKeySecretAnnotations() throws Exception {
        Field field = AbstractRazorpayTask.class.getDeclaredField("keySecret");
        PluginProperty pluginProperty = field.getAnnotation(PluginProperty.class);
        assertThat(pluginProperty).isNotNull();
        assertThat(pluginProperty.secret()).isTrue();
        assertThat(pluginProperty.group()).isEqualTo("connection");
    }

    @Test
    @DisplayName("Verify package-info subgroup annotation")
    void shouldVerifyPackageInfoSubGroup() {
        Package pkg = Package.getPackage("io.kestra.plugin.razorpay");
        assertThat(pkg).isNotNull();

        PluginSubGroup subGroup = pkg.getAnnotation(PluginSubGroup.class);
        assertThat(subGroup).isNotNull();
        assertThat(subGroup.title()).isEqualTo("Razorpay");
        assertThat(Arrays.asList(subGroup.categories()))
            .contains(PluginSubGroup.PluginCategory.BUSINESS);
    }
}
