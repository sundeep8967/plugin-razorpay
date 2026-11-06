package io.kestra.plugin.razorpay;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Capture an authorized Razorpay payment",
    description = "Captures an authorized payment to finalize settlement with Razorpay."
)
@Plugin(
    examples = {
        @Example(
            title = "Capture an authorized payment with specified amount and currency",
            full = true,
            code = """
                id: capture_payment_example
                namespace: company.team

                tasks:
                  - id: capture_payment
                    type: io.kestra.plugin.razorpay.CapturePayment
                    keyId: "{{ secret('RAZORPAY_KEY_ID') }}"
                    keySecret: "{{ secret('RAZORPAY_KEY_SECRET') }}"
                    paymentId: "pay_29QQoUBi66xm2f"
                    amount: 50000
                    currency: "INR"
                """
        )
    }
)
public class CapturePayment extends AbstractRazorpayTask implements RunnableTask<CapturePayment.Output> {

    @NotNull
    @Schema(
        title = "Payment ID",
        description = "The unique identifier of the payment to capture."
    )
    @PluginProperty(group = "main")
    private Property<String> paymentId;

    @NotNull
    @Schema(
        title = "Capture Amount",
        description = "The amount to capture in the smallest currency subunit such as paise for INR."
    )
    @PluginProperty(group = "main")
    private Property<Long> amount;

    @Schema(
        title = "Currency Code",
        description = "The ISO currency code such as INR or USD. Defaults to INR."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<String> currency = Property.ofValue("INR");

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String resolvedPaymentId = runContext.render(this.paymentId).as(String.class).orElseThrow();
        Long resolvedAmount = runContext.render(this.amount).as(Long.class).orElseThrow();
        String resolvedCurrency = runContext.render(this.currency).as(String.class).orElse("INR");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amount", resolvedAmount);
        payload.put("currency", resolvedCurrency);

        String jsonPayload = MAPPER.writeValueAsString(payload);

        logger.debug("Capturing Razorpay payment {} with amount {} {}", resolvedPaymentId, resolvedAmount, resolvedCurrency);

        HttpRequest request = newRequestBuilder(runContext, "/payments/" + resolvedPaymentId + "/capture")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
            .build();

        String responseBody = executeRequest(request);
        Map<String, Object> responseMap = MAPPER.readValue(responseBody, MAP_TYPE_REFERENCE);

        logger.info("Successfully captured Razorpay payment {}", resolvedPaymentId);

        return Output.builder()
            .id(toStringVal(responseMap.get("id")))
            .status(toStringVal(responseMap.get("status")))
            .amount(toLong(responseMap.get("amount")))
            .currency(toStringVal(responseMap.get("currency")))
            .captured(toBoolean(responseMap.get("captured")))
            .rawResponse(responseMap)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Payment ID",
            description = "The unique identifier of the captured payment."
        )
        private final String id;

        @Schema(
            title = "Payment Status",
            description = "The status of the payment after capture."
        )
        private final String status;

        @Schema(
            title = "Captured Amount",
            description = "The captured payment amount in currency subunits."
        )
        private final Long amount;

        @Schema(
            title = "Currency Code",
            description = "The currency code of the captured payment."
        )
        private final String currency;

        @Schema(
            title = "Captured Flag",
            description = "Boolean flag confirming whether the payment was captured."
        )
        private final Boolean captured;

        @Schema(
            title = "Raw Response",
            description = "The full response map returned by the Razorpay API."
        )
        private final Map<String, Object> rawResponse;
    }
}
