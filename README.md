# E-Commerce Microservices Backend

A production-style, cloud-native e-commerce backend built with **Spring Boot 3.x** and **Spring Cloud**. The system is decomposed into independent microservices that communicate over REST and an event-driven message bus, secured with **Keycloak/OAuth2**, orchestrated with **Docker Compose**, and observable via **Grafana + Prometheus + Loki**.

---

## Architecture Overview

```
                         ┌────────────────────────────────┐
                         │        API Gateway (8080)       │
                         │   Spring Cloud Gateway          │
                         │   • JWT validation              │
                         │   • Rate limiting (Redis)       │
                         │   • Circuit breaker (Resilience4j)
                         └────────┬───────────────────────┘
                                  │  routes
          ┌───────────────────────┼───────────────────────┐
          ▼                       ▼                       ▼
  ┌──────────────┐      ┌──────────────────┐    ┌──────────────────┐
  │ User Service │      │ Product Service  │    │  Order Service   │
  │  MongoDB     │      │  PostgreSQL      │    │  PostgreSQL      │
  │  Keycloak    │      │  REST API        │    │  Feign Client    │
  └──────────────┘      └──────────────────┘    └────────┬─────────┘
                                                          │ Kafka event
                                                          ▼
                                                ┌──────────────────────┐
                                                │ Notification Service  │
                                                │  Kafka Consumer       │
                                                └──────────────────────┘

  ┌────────────────────────────────────────────────────────────────────┐
  │  Infrastructure: Eureka  │  Config Server  │  Zipkin  │  RabbitMQ  │
  └────────────────────────────────────────────────────────────────────┘

  ┌────────────────────────────────────────────────────────────────────┐
  │  Observability: Prometheus  │  Grafana  │  Loki  │  Grafana Alloy  │
  └────────────────────────────────────────────────────────────────────┘
```

---

## Services

| Service | Port | Description | DB |
|---|---|---|---|
| **Config Server** | 8888 | Centralised external configuration via Spring Cloud Config | — |
| **Eureka Server** | 8761 | Service registry for dynamic service discovery | — |
| **API Gateway** | 8080 | Single entry point; handles routing, auth, rate limiting, circuit breaking | Redis |
| **User Service** | 8082 | User registration, profile management, Keycloak admin integration | MongoDB |
| **Product Service** | 8081 | Product catalogue CRUD with paginated listing | PostgreSQL |
| **Order Service** | 8083 | Cart management, order creation, inter-service calls via OpenFeign | PostgreSQL |
| **Notification Service** | — | Kafka consumer that processes `OrderCreatedEvent` for async notifications | — |

---

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 3.x, Spring Cloud 2023 |
| API | REST (Spring Web MVC / WebFlux) |
| Auth | Keycloak 26, OAuth2 / JWT |
| Service Discovery | Spring Cloud Netflix Eureka |
| API Gateway | Spring Cloud Gateway (reactive) |
| Config Management | Spring Cloud Config Server (native & Git) |
| Inter-service Comm | OpenFeign, Spring RestClient |
| Messaging | Apache Kafka, RabbitMQ |
| Databases | PostgreSQL, MongoDB |
| Resilience | Resilience4j (circuit breaker, retry), Redis rate limiter |
| Distributed Tracing | Zipkin + Micrometer |
| Observability | Prometheus, Grafana, Loki, Grafana Alloy |
| Containerisation | Docker, Docker Compose |
| Build | Maven, Jib (containerless Docker build) |

---

## Getting Started

### Prerequisites

- Docker & Docker Compose
- Java 21+
- Maven 3.9+

### 1. Configure Environment

Create a `.env` file inside `deploy/docker/`:

```env
POSTGRES_USER=ecom_user
POSTGRES_PASSWORD=ecom_pass
DB_USER=ecom_user
DB_PASSWORD=ecom_pass

RABBITMQ_HOST=rabbitmq
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=guest
RABBITMQ_PASSWORD=guest
RABBITMQ_VHOST=guest

MONGO_URI=mongodb://mongo:27017/userdb

ZIPKIN_URL=http://zipkin:9411/api/v2/spans

PGADMIN_DEFAULT_EMAIL=admin@ecom.local
PGADMIN_DEFAULT_PASSWORD=admin
```

### 2. Build All Services

```bash
cd deploy/docker
chmod +x build-projects.sh
./build-projects.sh
```

### 3. Start the Stack

```bash
cd deploy/docker
docker compose up -d
```

### 4. Access Services

| Service | URL |
|---|---|
| API Gateway | http://localhost:8080 |
| Eureka Dashboard | http://localhost:8761 |
| Keycloak Admin | http://localhost:8443 |
| PgAdmin | http://localhost:5050 |
| Zipkin UI | http://localhost:9411 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 |

---

## API Reference


### Product Service

```
GET    /api/products         — list all products (paginated)
GET    /api/products/{id}    — get product by ID
POST   /api/products         — create product  [ADMIN]
PUT    /api/products/{id}    — update product  [ADMIN]
DELETE /api/products/{id}    — delete product  [ADMIN]
```

### User Service

```
POST   /api/users            — register a new user
GET    /api/users/{id}       — get user profile
```

### Order Service

```
POST   /api/cart             — add item to cart
GET    /api/cart             — view cart
POST   /api/orders           — place order (triggers Kafka event)
GET    /api/orders           — list orders for logged-in user
```

---

## Key Design Decisions

**Centralised Config** — All service configs (datasource URLs, Kafka brokers, feature flags) are served by Spring Cloud Config Server from classpath. No config is hardcoded in any service JAR.

**JWT at the Gateway** — The gateway validates OAuth2 JWT tokens issued by Keycloak before forwarding requests. Downstream services trust the propagated `X-User-ID` header, keeping each service stateless.

**Event-Driven Order Flow** — When an order is placed, the Order Service publishes an `OrderCreatedEvent` to Kafka. The Notification Service consumes this event asynchronously, decoupling notification logic from the order transaction.

**Circuit Breaking** — The gateway wraps product-service calls in a Resilience4j circuit breaker with a `/fallback/products` handler, preventing cascading failures under load.

**Rate Limiting** — Redis-backed rate limiting at the gateway (10 requests/s burst of 20) protects downstream services from traffic spikes.

---

## Project Structure

```
ecom-microservices/
├── configserver/          # Spring Cloud Config Server
├── gateway/               # API Gateway (routing, auth, rate limiting)
├── user/                  # User registration + Keycloak admin
├── product/               # Product catalogue
├── order/                 # Cart & order management
├── notification/          # Async event consumer
└── deploy/
    └── docker/
        ├── docker-compose.yml
        ├── build-projects.sh
        └── build-images-jib.sh
```

---

