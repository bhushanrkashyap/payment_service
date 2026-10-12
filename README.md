# Payment Service

Enterprise-grade payment processing microservice for the high-concurrency Train Ticket Booking Platform. Built with **Spring Boot 4.1.0** (Java 21), **Citus / PostgreSQL**, **Apache Kafka**, **Bucket4j Rate Limiting**, and **Flyway**.

---

## Features

- **Strict Idempotency**: Cryptographically guaranteed idempotency for all payment requests and webhook callbacks (`Idempotency-Key` tracking and distributed transaction reconciliation).
- **Payment Gateway Integration**: Pluggable support for **Sandbox** (local testing) and **Razorpay** (production).
- **Event-Driven Architecture**: Fully integrated with **Apache Kafka** for emitting `payment.success`, `payment.failed`, and receiving booking events with dead-letter queue (DLQ) support.
- **Database Sharding Ready**: Optimized for Citus Distributed PostgreSQL with schema isolation (`payment_service` schema).
- **Bucket4j Token Bucket Rate Limiting**: Per-client and per-endpoint rate limits protecting critical initiation APIs from thundering herds.
- **Automated Recovery Worker**: Background reconciliation job (`PaymentRecoveryJob`) detecting and settling pending/stale orders.
- **Security & RBAC**: Stateless JWT-based authentication filter and strict security policy with granular permissions.

---

## Tech Stack

- **Java**: 21 (Eclipse Temurin)
- **Spring Boot**: 4.1.0 (WebMVC, Data JPA, Security, Actuator, Kafka, Validation)
- **Database**: PostgreSQL / Citus 13.0 with Flyway migrations (`db/migration/V1..V5`)
- **Messaging**: Apache Kafka (Kafka Clients 4.2.1)
- **Rate Limiting**: Bucket4j 8.10.1
- **Build Tool**: Maven 3.9+ / Maven Wrapper (`mvnw`)
- **Containerization**: Multi-stage Alpine Docker image with non-root security execution

---

## Environment Configuration

Configure via environment variables or a `.env` file:

| Variable | Default | Description |
|:---|:---|:---|
| `SERVER_PORT` | `8083` | HTTP service port |
| `PAYMENT_DB_URL` | `jdbc:postgresql://localhost:5432/payment_service` | PostgreSQL / Citus JDBC URL |
| `PAYMENT_DB_USERNAME` | `postgres` | Database user |
| `PAYMENT_DB_PASSWORD` | *(empty)* | Database password |
| `BOOKING_SERVICE_URL` | `http://localhost:8082` | Booking service internal URL |
| `KAFKA_BOOTSTRAP_SERVERS`| `localhost:9092` | Kafka broker address |
| `PAYMENT_PROVIDER` | `SANDBOX` | Gateway provider: `SANDBOX` or `RAZORPAY` |
| `RAZORPAY_KEY_ID` | *(empty)* | Razorpay Key ID |
| `RAZORPAY_KEY_SECRET` | *(empty)* | Razorpay Secret |
| `RAZORPAY_WEBHOOK_SECRET`| *(empty)* | Razorpay Webhook Signing Secret |
| `JWT_SECRET` | *(system default)* | HMAC-SHA secret for token verification |

---

## Running with Docker

### Multi-stage Build & Run
```bash
# Build standalone image
docker build -t train-ticket-payment:latest .

# Run container
docker run -d \
  -p 8083:8083 \
  -e PAYMENT_DB_URL=jdbc:postgresql://host.docker.internal:5432/payment_service \
  -e PAYMENT_DB_USERNAME=payment_service \
  -e PAYMENT_DB_PASSWORD=YourPassword \
  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  --name payment-service \
  train-ticket-payment:latest
```

### Healthcheck
```bash
curl -f http://localhost:8083/actuator/health
```

---

## Local Development (Maven)

```bash
# Compile and run tests
./mvnw clean test

# Package JAR
./mvnw clean package -DskipTests

# Run service locally
java -jar target/payment_service-0.0.1-SNAPSHOT.jar
```

---

## API Endpoints

| Method | Endpoint | Description |
|:---|:---|:---|
| `POST` | `/api/v1/payments/initiate` | Initiate payment for a booking (`Idempotency-Key` header required) |
| `POST` | `/api/v1/payments/verify` | Verify payment callback / signature |
| `GET` | `/api/v1/payments/{paymentId}` | Retrieve payment status & receipt details |
| `GET` | `/api/v1/payments/booking/{bookingId}` | Get payment by booking ID |
| `POST` | `/api/v1/payments/webhook/razorpay` | Razorpay webhook listener with signature verification |
| `GET` | `/actuator/health` | Service health status |
