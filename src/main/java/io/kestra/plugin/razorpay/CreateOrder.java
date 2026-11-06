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
    title = "Create a Razorpay order",
    description = "Initiates a new order in Razorpay to track payment collection and lifecycle states."
)
@Plugin(
    examples = {
        @Example(
            title = "Create a standard order with INR currency",
            full = true,
            code = """
                id: create_razorpay_order
                namespace: company.team

                tasks:
                  - id: create_order
                    type: io.kestra.plugin.razorpay.CreateOrder
                    keyId: "{{ secret('RAZORPAY_KEY_ID') }}"
                    keySecret: "{{ secret('RAZORPAY_KEY_SECRET') }}"
                    amount: 50000
                    currency: "INR"
                    receipt: "rcpt_order_1001"
                    notes:
                      department: "finance"
                      order_ref: "ORD-5544"
                """
        ),
        @Example(
            title = "Create an order allowing partial payments",
            full = true,
            code = """
                id: create_partial_payment_order
                namespace: company.team

                tasks:
                  - id: create_order
                    type: io.kestra.plugin.razorpay.CreateOrder
                    keyId: "{{ secret('RAZORPAY_KEY_ID') }}"
                    keySecret: "{{ secret('RAZORPAY_KEY_SECRET') }}"
                    amount: 150000
                    receipt: "rcpt_order_1002"
                    partialPayment: true
                """
        )
    }
)
public class CreateOrder extends AbstractRazorpayTask implements RunnableTask<CreateOrder.Output> {

    @NotNull
    @Schema(
        title = "Order Amount",
        description = "Payment amount in the smallest currency subunit such as paise for INR."
    )
    @PluginProperty(group = "main")
    private Property<Long> amount;

    @Schema(
        title = "Currency Code",
        description = "Three-letter ISO-4217 currency code. Defaults to INR."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<String> currency = Property.ofValue("INR");

    @Schema(
        title = "Receipt Identifier",
        description = "Merchant order reference or receipt number with a maximum length of 40 characters."
    )
    @PluginProperty(group = "main")
    private Property<String> receipt;

    @Schema(
        title = "Order Notes",
        description = "Key-value dictionary containing custom metadata up to 15 key-value pairs."
    )
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> notes;

    @Schema(
        title = "Partial Payment Allowed",
        description = "Whether customers are allowed to make partial payments against this order."
    )
    @PluginProperty(group = "advanced")
    private Property<Boolean> partialPayment;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        Long resolvedAmount = runContext.render(this.amount).as(Long.class).orElseThrow();
        String resolvedCurrency = runContext.render(this.currency).as(String.class).orElse("INR");
        String resolvedReceipt = runContext.render(this.receipt).as(String.class).orElse(null);
        Map<String, Object> resolvedNotes = runContext.render(this.notes).asMap(String.class, Object.class);
        Boolean resolvedPartialPayment = runContext.render(this.partialPayment).as(Boolean.class).orElse(null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amount", resolvedAmount);
        payload.put("currency", resolvedCurrency);

        if (Objects.nonNull(resolvedReceipt) && resolvedReceipt.isBlank() == false) {
            payload.put("receipt", resolvedReceipt);
        }

        if (Objects.nonNull(resolvedNotes) && resolvedNotes.isEmpty() == false) {
            payload.put("notes", resolvedNotes);
        }

        if (Objects.nonNull(resolvedPartialPayment)) {
            payload.put("partial_payment", resolvedPartialPayment);
        }

        String jsonPayload = MAPPER.writeValueAsString(payload);

        HttpRequest request = newRequestBuilder(runContext, "/orders")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
            .build();

        logger.debug("Creating Razorpay order for amount {} {}", resolvedAmount, resolvedCurrency);

        String responseBody = executeRequest(request);
        Map<String, Object> rawResponse = MAPPER.readValue(responseBody, MAP_TYPE_REFERENCE);

        logger.info("Successfully created Razorpay order with ID '{}'", rawResponse.get("id"));

        return Output.builder()
            .id(toStringVal(rawResponse.get("id")))
            .entity(toStringVal(rawResponse.get("entity")))
            .amount(toLong(rawResponse.get("amount")))
            .amountPaid(toLong(rawResponse.get("amount_paid")))
            .amountDue(toLong(rawResponse.get("amount_due")))
            .currency(toStringVal(rawResponse.get("currency")))
            .receipt(toStringVal(rawResponse.get("receipt")))
            .status(toStringVal(rawResponse.get("status")))
            .createdAt(toInstant(rawResponse.get("created_at")))
            .rawResponse(rawResponse)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Order ID",
            description = "The unique identifier of the created order."
        )
        private final String id;

        @Schema(
            title = "Entity Type",
            description = "The entity type name, which is order."
        )
        private final String entity;

        @Schema(
            title = "Order Amount",
            description = "The order amount in currency subunits."
        )
        private final Long amount;

        @Schema(
            title = "Amount Paid",
            description = "The amount paid against the order in currency subunits."
        )
        private final Long amountPaid;

        @Schema(
            title = "Amount Due",
            description = "The remaining amount due on the order in currency subunits."
        )
        private final Long amountDue;

        @Schema(
            title = "Currency Code",
            description = "The ISO-4217 currency code for the order."
        )
        private final String currency;

        @Schema(
            title = "Receipt Identifier",
            description = "The merchant receipt or order reference identifier."
        )
        private final String receipt;

        @Schema(
            title = "Order Status",
            description = "The current lifecycle status of the order."
        )
        private final String status;

        @Schema(
            title = "Creation Timestamp",
            description = "The timestamp when the order was created."
        )
        private final Instant createdAt;

        @Schema(
            title = "Raw Response",
            description = "The complete response map returned by the Razorpay API."
        )
        private final Map<String, Object> rawResponse;
    }
}
