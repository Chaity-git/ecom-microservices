package com.ecommerce.gateway.security;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Derives X-User-ID from the validated JWT and injects it downstream.
 *
 * Without this filter the header arrives straight from the client, and every
 * downstream @RequestHeader("X-User-ID") is trivially forgeable — anyone could
 * send X-User-ID: someone-else and operate as that user. The header is removed
 * before being re-added so a client-supplied value can never survive.
 *
 * Downstream services are only safe to trust this header if they are
 * unreachable except through the gateway. Enforce that with a network policy.
 */
@Component
public class UserIdPropagationFilter implements GlobalFilter, Ordered {

    public static final String USER_ID_HEADER = "X-User-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(auth -> exchange.mutate()
                        .request(r -> r
                                .headers(h -> h.remove(USER_ID_HEADER))
                                .header(USER_ID_HEADER, auth.getToken().getSubject()))
                        .build())
                .defaultIfEmpty(stripHeader(exchange))
                .flatMap(chain::filter);
    }

    /** Unauthenticated requests must not carry a spoofed header either. */
    private ServerWebExchange stripHeader(ServerWebExchange exchange) {
        return exchange.mutate()
                .request(r -> r.headers(h -> h.remove(USER_ID_HEADER)))
                .build();
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;   // after authentication has populated the context
    }
}
