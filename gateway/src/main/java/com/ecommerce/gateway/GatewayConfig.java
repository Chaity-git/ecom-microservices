package com.ecommerce.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Configuration
public class GatewayConfig {

    /** replenishRate=10/s, burstCapacity=20, 1 token per request. Redis-backed so the
     *  limit is global across gateway replicas rather than per-instance. */
    @Bean
    public RedisRateLimiter redisRateLimiter() {
        return new RedisRateLimiter(10, 20, 1);
    }

    /**
     * Rate-limit per authenticated user, not per remote address.
     *
     * Keying on the remote address collapses every user behind a load balancer or
     * ingress into a single bucket, so one busy client throttles everybody. The JWT
     * subject is stable and genuinely per-user. Unauthenticated traffic falls back
     * to the remote address.
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getName())
                .defaultIfEmpty(exchange.getRequest().getRemoteAddress() == null
                        ? "anonymous"
                        : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress());
    }

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("product-service", r -> r
                        .path("/api/products/**")
                        .filters(f -> f
                                // 3 attempts with exponential backoff, GET only.
                                // Was 10 retries: with the breaker needing 5 calls to
                                // open, a struggling service was hit 11x per request
                                // before the breaker had enough samples to trip.
                                .retry(retry -> retry
                                        .setRetries(2)
                                        .setMethods(HttpMethod.GET)
                                        .setBackoff(Duration.ofMillis(100),
                                                    Duration.ofSeconds(2), 2, true))
                                .requestRateLimiter(config -> config
                                        .setRateLimiter(redisRateLimiter())
                                        .setKeyResolver(userKeyResolver()))
                                .circuitBreaker(config -> config
                                        .setName("ecomBreaker")
                                        .setFallbackUri("forward:/fallback/products")))
                        .uri("lb://PRODUCT-SERVICE"))
                .route("user-service", r -> r
                        .path("/api/users/**")
                        .uri("lb://USER-SERVICE"))
                .route("order-service", r -> r
                        .path("/api/orders/**", "/api/cart/**")
                        .uri("lb://ORDER-SERVICE"))
                .build();
        // The eureka-server routes were removed: they hardcoded http://localhost:8761,
        // which breaks as soon as the gateway runs in a container. Reach the Eureka
        // dashboard directly on :8761 instead of proxying it.
    }
}
