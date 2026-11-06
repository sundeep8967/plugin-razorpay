<p align="center">
  <a href="https://kestra.io">
    <img src="https://kestra.io/banner.png" alt="Kestra workflow orchestrator" width="700" />
  </a>
</p>

# Kestra Plugin Razorpay

The Razorpay plugin enables seamless orchestration of online payments, customer transactions, authorized captures, and merchant refunds directly from Kestra workflows via the [Razorpay API](https://razorpay.com/docs/api/).

Razorpay is a comprehensive payments solution in India and global markets, supporting credit and debit cards, UPI, netbanking, digital wallets, and international currencies.

## Features

- **Order Management (`io.kestra.plugin.razorpay.CreateOrder`)**: Generate payment orders for checkout collection, track payment attempts, enforce partial payment limits, and attach internal accounting metadata.
- **Payment Inquiries (`io.kestra.plugin.razorpay.FetchPayment`)**: Inspect detailed payment records, transaction statuses, payment methods, customer contact information, and gateway fee breakdowns.
- **Payment Capture (`io.kestra.plugin.razorpay.CapturePayment`)**: Finalize and capture authorized charges to ensure timely settlement and avoid authorization reversals.
- **Refund Processing (`io.kestra.plugin.razorpay.CreateRefund`)**: Process full or partial refunds for settled payments with support for instant or normal processing speeds and audit receipts.

## Architecture

- **Platform Integration**: Built for Kestra 1.3.39 and above on Java 21, packaged as a single-module plugin.
- **Native HTTP Transport**: Implemented using Java standard `java.net.http.HttpClient` with configurable connection and read timeouts.
- **HTTP Basic Authentication**: Automatically generates RFC 7617 Basic Authentication headers by encoding `keyId` and `keySecret`.
- **Dynamic Variable Rendering**: All task inputs use Kestra's `Property<T>` wrapper, enabling runtime templating with Pebble expressions, trigger inputs, previous task outputs, and environment variables.
- **Credential Protection**: The sensitive `keySecret` attribute is marked with `secret = true` in schema annotations and excluded from string representations via Lombok `@ToString.Exclude`.
- **Structured Error Handling**: API error responses from Razorpay are automatically parsed into descriptive `KestraRuntimeException` instances containing HTTP status codes, error codes, and human-readable descriptions.

## Configuration and Credentials

To interact with the Razorpay API, obtain your API Key ID and Key Secret from the Razorpay Dashboard (Settings -> API Keys).

Store both credentials securely inside the Kestra Secrets Manager:
- `RAZORPAY_KEY_ID`: Your public Key ID (example: `rzp_test_xxxxxxxxxxxxxx`).
- `RAZORPAY_KEY_SECRET`: Your private Key Secret (example: `xxxxxxxxxxxxxxxxxxxxxxxx`).

Reference secrets within workflow task definitions using the `{{ secret('KEY_NAME') }}` syntax:

```yaml
keyId: "{{ secret('RAZORPAY_KEY_ID') }}"
keySecret: "{{ secret('RAZORPAY_KEY_SECRET') }}"
```

Optional connection properties available on all tasks:
- `baseUrl`: Overrides default `https://api.razorpay.com/v1` for mock servers, proxies, or sandbox environments.
- `timeout`: Configures HTTP request duration as an ISO-8601 string (default `PT15S`).

## Quickstart Workflow Example

The following workflow demonstrates creating an order, fetching an authorized payment, capturing the transaction, and issuing a partial refund:

```yaml
id: razorpay_payment_lifecycle
namespace: company.fintech

tasks:
  - id: create_order
    type: io.kestra.plugin.razorpay.CreateOrder
    keyId: "{{ secret('RAZORPAY_KEY_ID') }}"
    keySecret: "{{ secret('RAZORPAY_KEY_SECRET') }}"
    amount: 75000
    currency: "INR"
    receipt: "rcpt_order_8820"
    notes:
      channel: "ecommerce_web"
      customerId: "cust_9921"

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

  - id: issue_refund
    type: io.kestra.plugin.razorpay.CreateRefund
    keyId: "{{ secret('RAZORPAY_KEY_ID') }}"
    keySecret: "{{ secret('RAZORPAY_KEY_SECRET') }}"
    paymentId: "{{ outputs.capture_payment.id }}"
    amount: 25000
    speed: "normal"
    receipt: "rcpt_ref_8820_1"
    notes:
      reason: "partial_return_item_a"
```

## Building and Testing

Prerequisites:
- Java 21 or higher installed.
- Gradle wrapper provided within the repository.

```bash
# Compile source code and execute unit test suite against embedded mock server
./gradlew test

# Run documentation linter to validate icons, metadata, and guides
./gradlew lintPluginDocs

# Run complete verification suite including tests, coverage, and doc-lint
./gradlew check

# Assemble shaded plugin jar for deployment to Kestra plugin directory
./gradlew shadowJar
```

## Documentation

- Full Kestra documentation: [kestra.io/docs](https://kestra.io/docs)
- Plugin Developer Guide: [kestra.io/docs/plugin-developer-guide](https://kestra.io/docs/plugin-developer-guide/)
- Razorpay API Reference: [razorpay.com/docs/api](https://razorpay.com/docs/api/)

## License

Apache 2.0 (c) Kestra Technologies.
