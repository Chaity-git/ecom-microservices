# E-Commerce Microservices Backend

A six-service Spring Cloud backend. Each service owns its own database, the gateway
handles authentication and resilience centrally, and order placement is event-driven so
notifications never block checkout.

Java 21 · Spring Boot 3.x · Spring Cloud 2023

---

## Architecture

```
                        ┌─────────────────────────────────────┐
   client ─────────────►│         API Gateway  :8080          │◄──► Redis
                        │  Spring Cloud Gateway (reactive)     │   (rate limiter)
                        │  • Keycloak JWT validation           │
                        │  • per-user rate limiting 10/s (b20) │
                        │  • Resilience4j circuit breaker      │
                        │  • derives X-User-ID from the token  │
                        └──┬──────────────┬──────────────┬─────┘
                           ▼              ▼              ▼
                  ┌────────────┐  ┌────────────┐  ┌──────────────┐
                  │ User :8082 │  │Product:8081│  │ Order  :8083 │
                  │  MongoDB   │  │ PostgreSQL │  │  PostgreSQL  │
                  │  Keycloak  │  │ catalogue  │  │ HTTP client ─┼──► Product
                  │  admin API │  │            │  │              │──► User
                  └────────────┘  └────────────┘  └──────┬───────┘
                                                          │ OrderCreatedEvent
                                                          ▼ (Spring Cloud Stream)
                                                    ┌──────────────┐
                                                    │  Kafka       │
                                                    └──────┬───────┘
                                                           ▼
                                                 ┌────────────────────┐
                                                 │ Notification :8084 │
                                                 └────────────────────┘

  Platform:       Config Server :8888   │   Eureka :8761
  Observability:  Micrometer → Zipkin (tracing)  │  Prometheus + Grafana (metrics)
```

## Services

| Service | Port | Database | Responsibility |
|---|---|---|---|
| Config Server | 8888 | — | Serves every service's configuration; nothing is baked into a JAR |
| Eureka | 8761 | — | Service registry; services resolve each other by logical name |
| API Gateway | 8080 | Redis | Routing, JWT validation, rate limiting, circuit breaking |
| User | 8082 | MongoDB | Registration and profiles, provisioned into Keycloak |
| Product | 8081 | PostgreSQL | Catalogue CRUD, paginated listing, admin-only writes |
| Order | 8083 | PostgreSQL | Cart and orders; calls Product and User |
| Notification | 8084 | — | Consumes `OrderCreatedEvent` asynchronously |

**Database per service.** Product and Order are relational and use PostgreSQL. A user
profile is a document with an embedded address and optional fields, so User uses MongoDB.
No service reads another's database — that is what makes them independently deployable,
and the cost is no cross-service joins and no distributed transactions.

## Tech stack

| Layer | Technology |
|---|---|
| Language / framework | Java 21, Spring Boot 3.x, Spring Cloud 2023 |
| API gateway | Spring Cloud Gateway (reactive) |
| Service discovery | Spring Cloud Netflix Eureka |
| Configuration | Spring Cloud Config Server |
| Security | Keycloak, OAuth2, JWT |
| Inter-service calls | Spring HTTP Interfaces (`@HttpExchange`) over a load-balanced `RestClient` |
| Messaging | Spring Cloud Stream with the Apache Kafka binder |
| Databases | PostgreSQL, MongoDB |
| Resilience | Resilience4j (circuit breaker, retry), Redis rate limiter |
| Observability | Micrometer, Zipkin, Prometheus, Grafana |
| Build & run | Maven, Docker, Docker Compose |

---

## Key design decisions

### Authentication at the gateway, `X-User-ID` downstream

The gateway validates the OAuth2 JWT against Keycloak's JWKS endpoint — signatures are
verified locally, with no per-request call to Keycloak. It then extracts the `sub` claim
and injects it as `X-User-ID`, **stripping any client-supplied value first**, so downstream
services stay stateless and never re-parse the token.

This is only safe if the services are unreachable except through the gateway. Without that
network boundary, anyone could call Order directly and forge the header. Enforce it with a
network policy in any real deployment.

### Inter-service calls use HTTP Interfaces, not Feign

```java
@HttpExchange
public interface ProductServiceClient {
    @GetExchange("/api/products/{id}")
    ProductResponse getProductDetails(@PathVariable String id);
}
```

`HttpServiceProxyFactory` generates the implementation at runtime over a `@LoadBalanced`
`RestClient`, so `http://product-service` resolves through Eureka. This is the Spring 6
replacement for OpenFeign: same declarative style, no extra dependency, and it works with
`RestClient` rather than the older `RestTemplate` stack.

### Remote calls never happen inside a transaction

`CartService` makes its HTTP calls to Product and User first, then delegates persistence to
`CartPersistenceService`, which is the only `@Transactional` bean in the flow. Holding a
PostgreSQL connection across a network round-trip is how a connection pool gets exhausted
by a single slow dependency — and with retries configured on top, one failing service could
pin a connection for the full retry budget.

The persistence logic lives in a separate bean on purpose: Spring's transaction proxy only
wraps calls arriving from outside the bean, so a self-invoked private method would silently
run with no transaction at all.

### Order placement is atomic; the event is published after commit

`OrderPersistenceService.placeOrder` saves the order and clears the cart in one
transaction, so a failure can't leave an order committed with the cart still populated.
The `OrderCreatedEvent` is published only after that transaction commits.

**Known limitation:** the commit and the publish are not atomic. If the process dies
between them, the order exists with no event. The correct fix is the transactional outbox
pattern — write the event into an outbox table inside the same transaction and have a
poller publish it.

### Messaging goes through Spring Cloud Stream

```java
streamBridge.send("createOrder-out-0", event);
```

`createOrder-out-0` is a logical binding, mapped by configuration to a destination on the
Kafka binder. The consumer is a plain function:

```java
@Bean
public Consumer<OrderCreatedEvent> orderCreated() { ... }
```

The binding abstraction means swapping brokers is a dependency and a config block, not a
code change. The destination is named `order.exchange` because this started on RabbitMQ
and moved to Kafka for retention and consumer-group replay; the name is a leftover from
that migration.

The consumer group `notification-group` is what gives offset tracking, so events published
while the notification service is down are replayed when it restarts.

### Circuit breaker before retry

The gateway wraps product-service calls in a Resilience4j circuit breaker with a
`/fallback/products` handler, and retries GETs up to twice with exponential backoff. Order
matters: the breaker sheds load once the downstream is consistently failing, so retries
never pile onto a service that is already struggling.

### Rate limiting is per user, not per address

The Redis rate limiter keys on the JWT subject. Keying on the remote address would collapse
every user behind a load balancer into one bucket, letting a single busy client throttle
everybody. Redis-backed rather than in-memory so the limit is global across gateway replicas.

---

## Running it

**Prerequisites:** Docker and Docker Compose, Java 21, Maven 3.9+.

```bash
# 1. environment
cd deploy/docker
cp .env.example .env          # then edit the values

# 2. build all service images
chmod +x build-projects.sh && ./build-projects.sh

# 3. start
docker compose up -d
```

| Service | URL |
|---|---|
| API Gateway | http://localhost:8080 |
| Eureka | http://localhost:8761 |
| Keycloak | http://localhost:8443 |
| Zipkin | http://localhost:9411 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 |

## API

All endpoints require a Keycloak-issued bearer token except public catalogue reads and
registration.

**Product** — `/api/products`

| Method | Path | Access |
|---|---|---|
| GET | `/api/products` | public, paginated |
| GET | `/api/products/{id}` | public |
| POST / PUT / DELETE | `/api/products{/id}` | `ROLE_ADMIN` |

**User** — `/api/users`

| Method | Path | Access |
|---|---|---|
| POST | `/api/users` | public (registration) |
| GET | `/api/users/{id}` | authenticated |

**Cart and orders** — `/api/cart`, `/api/orders`

| Method | Path | Notes |
|---|---|---|
| POST | `/api/cart` | validates stock via Product, price from the catalogue |
| GET | `/api/cart` | current user's cart |
| DELETE | `/api/cart/items/{productId}` | |
| POST | `/api/orders` | places the order, publishes `OrderCreatedEvent` |
| GET | `/api/orders` | current user's orders |

---

## Project structure

```
ecom-microservices/
├── configserver/     Spring Cloud Config Server + per-service YAML
├── gateway/          routing, SecurityConfig, UserIdPropagationFilter, FallbackController
├── user/             MongoDB documents, Keycloak admin integration
├── product/          JPA catalogue
├── order/            cart + orders, HTTP clients, Cloud Stream producer
├── notification/     Cloud Stream consumer
└── deploy/docker/    compose, Prometheus, Grafana, image build scripts
```

## Known limitations

An honest list, because a system this size has them.

- **No transactional outbox.** The order commit and the event publish are not atomic.
- **`X-User-ID` trust requires network isolation.** Without it, services are callable
  directly and the header is forgeable.
- **Test coverage is thin.** The highest-value addition would be an integration test that
  places an order and asserts the event is published.
- **No Saga for failed payments.** Cancelling a committed order across service boundaries
  needs a compensating transaction.
- **User registration is a distributed write with no rollback.** If the local save fails
  after Keycloak provisioning succeeds, the Keycloak user is orphaned.
