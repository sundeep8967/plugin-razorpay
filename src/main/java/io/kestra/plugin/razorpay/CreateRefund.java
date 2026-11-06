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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Issue a refund for a Razorpay payment",
    description = "Issues a full or partial refund for a captured Razorpay payment."
)
@Plugin(
    examples = {
        @Example(
            title = "Issue a partial refund for a payment",
            full = true,
            code = """
                id: refund_payment_partial
                namespace: company.team

                tasks:
                  - id: partial_refund
                    type: io.kestra.plugin.razorpay.CreateRefund
                    keyId: "{{ secret('RAZORPAY_KEY_ID') }}"
                    keySecret: "{{ secret('RAZORPAY_KEY_SECRET') }}"
                    paymentId: "pay_29QQoUBi66xm2f"
                    amount: 25000
                    speed: "normal"
                    notes:
                      reason: "customer_cancellation"
                    receipt: "rcpt_ref_1001"
                """
        ),
        @Example(
            title = "Issue a full refund for a payment",
            full = true,
            code = """
                id: refund_payment_full
                namespace: company.team

                tasks:
                  - id: full_refund
                    type: io.kestra.plugin.razorpay.CreateRefund
                    keyId: "{{ secret('RAZORPAY_KEY_ID') }}"
                    keySecret: "{{ secret('RAZORPAY_KEY_SECRET') }}"
                    paymentId: "pay_29QQoUBi66xm2f"
                """
        )
    }
)
public class CreateRefund extends AbstractRazorpayTask implements RunnableTask<CreateRefund.Output> {

    @NotNull
    @Schema(
        title = "Payment ID",
        description = "The unique identifier of the payment to refund."
    )
    @PluginProperty(group = "main")
    private Property<String> paymentId;

    @Schema(
        title = "Refund Amount",
        description = "The amount to refund in currency subunits. When omitted, a full refund is issued."
    )
    @PluginProperty(group = "main")
    private Property<Long> amount;

    @Schema(
        title = "Refund Speed",
        description = "Processing speed for the refund. Permitted values are normal or optimum. Defaults to normal."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<String> speed = Property.ofValue("normal");

    @Schema(
        title = "Refund Notes",
        description = "Key-value metadata pairs for the refund record. Maximum 15 key-value pairs."
    )
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> notes;

    @Schema(
        title = "Receipt Identifier",
        description = "Merchant internal reference identifier for the refund."
    )
    @PluginProperty(group = "main")
    private Property<String> receipt;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String resolvedPaymentId = runContext.render(this.paymentId).as(String.class).orElseThrow();
        Long resolvedAmount = runContext.render(this.amount).as(Long.class).orElse(null);
        String resolvedSpeed = runContext.render(this.speed).as(String.class).orElse("normal");
        Map<String, Object> resolvedNotes = runContext.render(this.notes).asMap(String.class, Object.class);
        String resolvedReceipt = runContext.render(this.receipt).as(String.class).orElse(null);

        Map<String, Object> payload = new LinkedHashMap<>();
        if (Objects.nonNull(resolvedAmount)) {
            payload.put("amount", resolvedAmount);
        }
        if (Objects.nonNull(resolvedSpeed) && resolvedSpeed.isBlank() == false) {
            payload.put("speed", resolvedSpeed);
        }
        if (Objects.nonNull(resolvedNotes) && resolvedNotes.isEmpty() == false) {
            payload.put("notes", resolvedNotes);
        }
        if (Objects.nonNull(resolvedReceipt) && resolvedReceipt.isBlank() == false) {
            payload.put("receipt", resolvedReceipt);
        }

        String jsonPayload = MAPPER.writeValueAsString(payload);

        logger.debug("Creating refund for Razorpay payment {}", resolvedPaymentId);

        HttpRequest request = newRequestBuilder(runContext, "/payments/" + resolvedPaymentId + "/refund")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
            .build();

        String responseBody = executeRequest(request);
        Map<String, Object> responseMap = MAPPER.readValue(responseBody, MAP_TYPE_REFERENCE);

        String refundId = toStringVal(responseMap.get("id"));
        logger.info("Successfully created refund {} for payment {}", refundId, resolvedPaymentId);

        return Output.builder()
            .id(refundId)
            .paymentId(toStringVal(responseMap.get("payment_id")))
            .amount(toLong(responseMap.get("amount")))
            .currency(toStringVal(responseMap.get("currency")))
            .status(toStringVal(responseMap.get("status")))
            .speedProcessed(toStringVal(responseMap.get("speed_processed")))
            .receipt(toStringVal(responseMap.get("receipt")))
            .createdAt(toInstant(responseMap.get("created_at")))
            .rawResponse(responseMap)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Refund ID",
            description = "The unique identifier of the created refund."
        )
        private final String id;

        @Schema(
            title = "Payment ID",
            description = "The identifier of the refunded payment."
        )
        private final String paymentId;

        @Schema(
            title = "Refund Amount",
            description = "The amount refunded in currency subunits."
        )
        private final Long amount;

        @Schema(
            title = "Currency Code",
            description = "The currency code of the refund."
        )
        private final String currency;

        @Schema(
            title = "Refund Status",
            description = "The status of the refund."
        )
        private final String status;

        @Schema(
            title = "Speed Processed",
            description = "The speed at which the refund was processed."
        )
        private final String speedProcessed;

        @Schema(
            title = "Receipt Identifier",
            description = "The merchant receipt reference for the refund."
        )
        private final String receipt;

        @Schema(
            title = "Creation Timestamp",
            description = "The timestamp when the refund was created."
        )
        private final Instant createdAt;

        @Schema(
            title = "Raw Response",
            description = "The full response map returned by the Razorpay API."
        )
        private final Map<String, Object> rawResponse;
    }
}
