# Changelog

## Security

**`X-User-ID` was forgeable.** The gateway validated the JWT but never set the header, so
it arrived from the client and every downstream `@RequestHeader("X-User-ID")` could be
spoofed. `UserIdPropagationFilter` now derives it from the token's `sub` claim and strips
any client-supplied value first.

**Authorisation was authentication only.** The role matchers in `SecurityConfig` were
commented out, leaving `anyExchange().authenticated()` — every authenticated user could
call every route. Product writes now require `ROLE_ADMIN`; catalogue reads and
registration are public.

**Removed `JwtAuthFilter`**, a disabled class whose body was `// TODO: Validate JWT Token`.
Real validation happens in `SecurityConfig`. Also removed the disabled `LoggingFilter` and
a `System.out.println` that logged extracted roles.

## Correctness

**Cart prices were hardcoded.** `CartService` fetched the product, checked its stock, then
stored `BigDecimal.valueOf(1000.00)` regardless — every cart line totalled 1000. It now
uses the catalogue price multiplied by quantity.

**Order placement was not atomic.** `createOrder` saved the order, cleared the cart and
published an event as three unrelated operations. `OrderPersistenceService` now does the
save and the clear in one transaction; the event is published after commit.

**`docker-compose.yml` was not valid YAML.** `notification-service.build` was indented
eight spaces instead of four, so `docker compose up` could not parse the file at all.
Postgres also had blank `POSTGRES_USER` and `POSTGRES_PASSWORD` values.

**Kafka broker addresses disagreed.** order-service used `kafka:9092`,
notification-service `localhost:9092`. Both now read `${KAFKA_BROKERS}`, with the value
set once in `.env`.

## Resource handling

**Remote calls ran inside a transaction.** `CartService` carried a class-level
`@Transactional` while making two HTTP calls, holding a PostgreSQL connection across both
round-trips. With `@Retry(maxAttempts=5, waitDuration=5s)` on top, one failing dependency
could pin a connection for roughly 25 seconds. Persistence moved to
`CartPersistenceService` — a separate bean, because Spring's transaction proxy does not
apply to self-invoked methods.

**Retry was amplifying failure.** The gateway retried GETs ten times with the circuit
breaker needing five calls to open, so a struggling service was hit eleven times per
request before the breaker had samples to trip. Now two retries with exponential backoff,
and the breaker sits ahead of retry in `CartService`.

**Rate limiting was per address, not per user.** The `KeyResolver` used the remote address,
so behind a load balancer every user shared one bucket. It now keys on the JWT subject.

**Removed the Eureka proxy routes**, which hardcoded `http://localhost:8761` and broke
whenever the gateway ran in a container.

## Configuration

- `.idea/` removed from version control; `.idea/` and `target/` added to `.gitignore`.
- `deploy/docker/.env.example` added as a template.
- Keycloak admin password and the MinIO root password read from the environment rather
  than being hardcoded in compose.

## Known limitations, unchanged

- No transactional outbox: the order commit and the event publish are not atomic.
- The notification consumer logs and does nothing else.
- All six test classes are default `contextLoads`.
- The RabbitMQ container remains in compose but no Java code uses it — messaging moved to
  Kafka. The `order.exchange` destination name is a leftover from that migration.
