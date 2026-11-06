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
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Fetch a Razorpay payment",
    description = "Retrieves detailed information and status for an individual payment by identifier."
)
@Plugin(
    examples = {
        @Example(
            title = "Fetch payment details by payment identifier",
            full = true,
            code = """
                id: fetch_razorpay_payment
                namespace: company.team

                tasks:
                  - id: fetch_payment
                    type: io.kestra.plugin.razorpay.FetchPayment
                    keyId: "{{ secret('RAZORPAY_KEY_ID') }}"
                    keySecret: "{{ secret('RAZORPAY_KEY_SECRET') }}"
                    paymentId: "pay_29QQoUBi66xm2f"
                """
        )
    }
)
public class FetchPayment extends AbstractRazorpayTask implements RunnableTask<FetchPayment.Output> {

    @NotNull
    @Schema(
        title = "Payment ID",
        description = "The unique identifier of the payment to retrieve."
    )
    @PluginProperty(group = "main")
    private Property<String> paymentId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String resolvedPaymentId = runContext.render(this.paymentId).as(String.class).orElseThrow();

        logger.debug("Fetching Razorpay payment with ID '{}'", resolvedPaymentId);

        HttpRequest request = newRequestBuilder(runContext, "/payments/" + resolvedPaymentId)
            .GET()
            .build();

        String responseBody = executeRequest(request);
        Map<String, Object> rawResponse = MAPPER.readValue(responseBody, MAP_TYPE_REFERENCE);

        logger.info("Successfully fetched Razorpay payment '{}' with status '{}'", resolvedPaymentId, rawResponse.get("status"));

        return Output.builder()
            .id(toStringVal(rawResponse.get("id")))
            .amount(toLong(rawResponse.get("amount")))
            .currency(toStringVal(rawResponse.get("currency")))
            .status(toStringVal(rawResponse.get("status")))
            .method(toStringVal(rawResponse.get("method")))
            .orderId(toStringVal(rawResponse.get("order_id")))
            .email(toStringVal(rawResponse.get("email")))
            .contact(toStringVal(rawResponse.get("contact")))
            .fee(toLong(rawResponse.get("fee")))
            .tax(toLong(rawResponse.get("tax")))
            .rawResponse(rawResponse)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Payment ID",
            description = "The unique identifier of the payment."
        )
        private final String id;

        @Schema(
            title = "Payment Amount",
            description = "The total payment amount in currency subunits."
        )
        private final Long amount;

        @Schema(
            title = "Currency Code",
            description = "The ISO-4217 currency code for the payment."
        )
        private final String currency;

        @Schema(
            title = "Payment Status",
            description = "The current status of the payment such as captured or authorized."
        )
        private final String status;

        @Schema(
            title = "Payment Method",
            description = "The payment instrument used such as card, upi, or netbanking."
        )
        private final String method;

        @Schema(
            title = "Order ID",
            description = "The associated Razorpay order identifier."
        )
        private final String orderId;

        @Schema(
            title = "Customer Email",
            description = "The email address of the customer."
        )
        private final String email;

        @Schema(
            title = "Customer Contact",
            description = "The contact phone number of the customer."
        )
        private final String contact;

        @Schema(
            title = "Transaction Fee",
            description = "The processing fee charged by Razorpay in currency subunits."
        )
        private final Long fee;

        @Schema(
            title = "Transaction Tax",
            description = "The tax amount charged on the transaction fee in currency subunits."
        )
        private final Long tax;

        @Schema(
            title = "Raw Response",
            description = "The complete response map returned by the Razorpay API."
        )
        private final Map<String, Object> rawResponse;
    }
}
