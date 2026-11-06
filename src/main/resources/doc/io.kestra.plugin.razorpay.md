# Razorpay

The Razorpay plugin enables seamless payment orchestration, automated order creation, payment capture, and refund processing through the [Razorpay API](https://razorpay.com/docs/api/).

Razorpay is a comprehensive payments platform powering commerce and financial operations, supporting debit cards, credit cards, UPI, net banking, digital wallets, and automated refunds.

## Authentication and Credentials

Razorpay APIs authenticate requests using HTTP Basic Authentication. Every API call requires an API Key ID as the username and an API Key Secret as the password.

### Storing Credentials in Kestra Secrets

Storing plaintext credentials inside flow definitions is strongly discouraged. Store your API keys securely in Kestra Secrets:

1. Obtain your API Key ID and API Key Secret from the Razorpay Dashboard under **Account & Settings** > **API Keys**.
2. Create secrets in your Kestra namespace or instance:
   - `RAZORPAY_KEY_ID`: Your public Key ID (for example, `rzp_test_1DP5mmOlF5G5ag`).
   - `RAZORPAY_KEY_SECRET`: Your confidential Key Secret.
3. Reference the stored secrets dynamically in task properties using the `secret()` function:

```yaml
keyId: "{{ secret('RAZORPAY_KEY_ID') }}"
keySecret: "{{ secret('RAZORPAY_KEY_SECRET') }}"
```

### Common Connection Properties

All Razorpay tasks extend a common base configuration and inherit the following connection properties:

| Property | Type | Default | Description |
|---|---|---|---|
| `keyId` | String | (required) | Razorpay API Key ID. Supports dynamic expressions. |
| `keySecret` | String | (required) | Razorpay API Key Secret. Marked secret to prevent exposure in logs. |
| `baseUrl` | String | `https://api.razorpay.com/v1` | Base endpoint URL. Override for custom mocks or staging endpoints. |
| `timeout` | Duration | `PT15S` | HTTP client request timeout duration. |

## Tasks

### `io.kestra.plugin.razorpay.CreateOrder`

Creates an order in Razorpay to track payment collection and lifecycle states. An order must be generated prior to collecting customer payments.

#### Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `amount` | Long | (required) | Amount in currency subunits such as paise for INR (for example, 50000 equals 500 INR). |
| `currency` | String | `INR` | Three-letter ISO-4217 currency code. |
| `receipt` | String | null | Merchant order or receipt reference (maximum 40 characters). |
| `notes` | Map<String, Object> | null | Key-value dictionary containing custom metadata up to 15 entries. |
| `partialPayment` | Boolean | null | Whether partial payments are accepted for this order. |

#### Outputs

| Output | Type | Description |
|---|---|---|
| `id` | String | Unique order identifier assigned by Razorpay. |
| `entity` | String | Entity type name, which is order. |
| `amount` | Long | Total order amount in currency subunits. |
| `amountPaid` | Long | Amount paid against the order in currency subunits. |
| `amountDue` | Long | Remaining amount due on the order in currency subunits. |
| `currency` | String | ISO-4217 currency code. |
| `receipt` | String | Merchant receipt identifier. |
| `status` | String | Order status (created, attempted, paid). |
| `createdAt` | Instant | Timestamp when the order was created. |
| `rawResponse` | Map<String, Object> | Complete response map returned by the Razorpay API. |

#### Example: Create an Order

```yaml
id: create_razorpay_order
namespace: company.payments

tasks:
  - id: create_order
    type: io.kestra.plugin.razorpay.CreateOrder
    keyId: "{{ secret('RAZORPAY_KEY_ID') }}"
    keySecret: "{{ secret('RAZORPAY_KEY_SECRET') }}"
    amount: 75000
    currency: "INR"
    receipt: "rcpt_ord_9001"
    notes:
      department: "sales"
      customer_tier: "enterprise"
```

---

### `io.kestra.plugin.razorpay.FetchPayment`

Retrieves detailed information and real-time status for an individual payment by identifier.

#### Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `paymentId` | String | (required) | Unique identifier of the payment to retrieve. |

#### Outputs

| Output | Type | Description |
|---|---|---|
| `id` | String | Unique identifier of the payment. |
| `amount` | Long | Payment amount in currency subunits. |
| `currency` | String | ISO-4217 currency code. |
| `status` | String | Payment status (created, authorized, captured, refunded, failed). |
| `method` | String | Payment instrument used (card, upi, netbanking, wallet). |
| `orderId` | String | Associated Razorpay order identifier. |
| `email` | String | Customer email address. |
| `contact` | String | Customer phone number. |
| `fee` | Long | Processing fee charged by Razorpay in currency subunits. |
| `tax` | Long | Service tax charged on transaction fee in currency subunits. |
| `rawResponse` | Map<String, Object> | Complete response map returned by the Razorpay API. |

#### Example: Fetch Payment Details

```yaml
id: fetch_razorpay_payment
namespace: company.payments

tasks:
  - id: fetch_payment
    type: io.kestra.plugin.razorpay.FetchPayment
    keyId: "{{ secret('RAZORPAY_KEY_ID') }}"
    keySecret: "{{ secret('RAZORPAY_KEY_SECRET') }}"
    paymentId: "pay_29QQoUBi66xm2f"
```

---

### `io.kestra.plugin.razorpay.CapturePayment`

Captures an authorized payment to finalize settlement with Razorpay. Used in two-step payment workflows where authorization and capture occur separately.

#### Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `paymentId` | String | (required) | Unique identifier of the payment to capture. |
| `amount` | Long | (required) | Amount to capture in currency subunits. |
| `currency` | String | `INR` | Three-letter ISO currency code. |

#### Outputs

| Output | Type | Description |
|---|---|---|
| `id` | String | Unique identifier of the captured payment. |
| `status` | String | Payment status after capture. |
| `amount` | Long | Captured payment amount in currency subunits. |
| `currency` | String | Currency code of the captured payment. |
| `captured` | Boolean | Flag confirming whether the payment was captured. |
| `rawResponse` | Map<String, Object> | Complete response map returned by the Razorpay API. |

#### Example: Capture Authorized Payment

```yaml
id: capture_razorpay_payment
namespace: company.payments

tasks:
  - id: capture_payment
    type: io.kestra.plugin.razorpay.CapturePayment
    keyId: "{{ secret('RAZORPAY_KEY_ID') }}"
    keySecret: "{{ secret('RAZORPAY_KEY_SECRET') }}"
    paymentId: "pay_29QQoUBi66xm2f"
    amount: 50000
    currency: "INR"
```

---

### `io.kestra.plugin.razorpay.CreateRefund`

Issues a full or partial refund for a captured payment. When amount is omitted, a full refund for the remaining balance is issued.

#### Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `paymentId` | String | (required) | Unique identifier of the payment to refund. |
| `amount` | Long | null | Amount to refund in currency subunits. When omitted, full refund is issued. |
| `speed` | String | `normal` | Processing speed for the refund (normal or optimum). |
| `notes` | Map<String, Object> | null | Custom metadata key-value pairs up to 15 entries. |
| `receipt` | String | null | Merchant internal reference identifier for the refund. |

#### Outputs

| Output | Type | Description |
|---|---|---|
| `id` | String | Unique identifier of the created refund. |
| `paymentId` | String | Identifier of the refunded payment. |
| `amount` | Long | Refunded amount in currency subunits. |
| `currency` | String | Currency code of the refund. |
| `status` | String | Refund status (processed, pending). |
| `speedProcessed` | String | Speed at which the refund was processed. |
| `receipt` | String | Merchant receipt reference for the refund. |
| `createdAt` | Instant | Timestamp when the refund was created. |
| `rawResponse` | Map<String, Object> | Complete response map returned by the Razorpay API. |

#### Example: Issue a Refund

```yaml
id: refund_razorpay_payment
namespace: company.payments

tasks:
  - id: issue_refund
    type: io.kestra.plugin.razorpay.CreateRefund
    keyId: "{{ secret('RAZORPAY_KEY_ID') }}"
    keySecret: "{{ secret('RAZORPAY_KEY_SECRET') }}"
    paymentId: "pay_29QQoUBi66xm2f"
    amount: 25000
    speed: "normal"
    receipt: "rcpt_ref_4401"
    notes:
      reason: "customer_requested"
```

## Error Handling

### API Error Responses

When the Razorpay API returns a non-2xx HTTP status code, the plugin parses the error response JSON and raises a `KestraRuntimeException`. The error message includes the HTTP status code, Razorpay error code, and error description:

```
Razorpay API request failed with HTTP 400 [BAD_REQUEST_ERROR]: Order amount exceeds maximum allowable limit.
```

Standard Razorpay error categories include:
- `BAD_REQUEST_ERROR`: Invalid request parameters, missing required fields, or format mismatches.
- `GATEWAY_ERROR`: Upstream banking provider or gateway timeout.
- `SERVER_ERROR`: Transient issue on the Razorpay API servers.

### Retries and Failure Recovery

Use Kestra execution resilience features to handle transient gateway failures or network interruptions:

```yaml
id: resilient_payment_fetch
namespace: company.payments

tasks:
  - id: fetch_payment
    type: io.kestra.plugin.razorpay.FetchPayment
    keyId: "{{ secret('RAZORPAY_KEY_ID') }}"
    keySecret: "{{ secret('RAZORPAY_KEY_SECRET') }}"
    paymentId: "pay_29QQoUBi66xm2f"
    retry:
      type: constant
      interval: PT5S
      maxAttempt: 3
    errors:
      - id: alert_on_failure
        type: io.kestra.plugin.core.log.Log
        message: "Failed to fetch payment details after retries"
```

## Complete Workflow Example

The following workflow demonstrates an end-to-end payment lifecycle: creating an order, fetching payment status, conditionally capturing the payment, and logging the result.

```yaml
id: razorpay_payment_pipeline
namespace: company.payments

tasks:
  - id: create_order
    type: io.kestra.plugin.razorpay.CreateOrder
    keyId: "{{ secret('RAZORPAY_KEY_ID') }}"
    keySecret: "{{ secret('RAZORPAY_KEY_SECRET') }}"
    amount: 50000
    currency: "INR"
    receipt: "order_receipt_101"
    notes:
      channel: "web_checkout"

  - id: fetch_payment
    type: io.kestra.plugin.razorpay.FetchPayment
    keyId: "{{ secret('RAZORPAY_KEY_ID') }}"
    keySecret: "{{ secret('RAZORPAY_KEY_SECRET') }}"
    paymentId: "pay_29QQoUBi66xm2f"

  - id: capture_payment
    type: io.kestra.plugin.razorpay.CapturePayment
    keyId: "{{ secret('RAZORPAY_KEY_ID') }}"
    keySecret: "{{ secret('RAZORPAY_KEY_SECRET') }}"
    paymentId: "{{ outputs.fetch_payment.id }}"
    amount: "{{ outputs.fetch_payment.amount }}"
    currency: "{{ outputs.fetch_payment.currency }}"

  - id: log_completion
    type: io.kestra.plugin.core.log.Log
    message: "Captured payment {{ outputs.capture_payment.id }} for order {{ outputs.create_order.id }}"
```
