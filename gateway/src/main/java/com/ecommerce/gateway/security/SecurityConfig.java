package com.ecommerce.gateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final String KEYCLOAK_CLIENT_ID = "oauth2-pkce";

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                // Disabled deliberately: this is a stateless JWT API with no cookies,
                // so there is no session for a CSRF attack to ride on.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange
                        // health and metrics stay open for Prometheus
                        .pathMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .pathMatchers("/fallback/**").permitAll()
                        // catalogue reads are public; writes are admin-only
                        .pathMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                        .pathMatchers(HttpMethod.POST,   "/api/products/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.PUT,    "/api/products/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.DELETE, "/api/products/**").hasRole("ADMIN")
                        // registration is open; everything else needs a valid token
                        .pathMatchers(HttpMethod.POST, "/api/users").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(jwt ->
                                jwt.jwtAuthenticationConverter(grantedAuthoritiesExtractor())))
                .build();
    }

    /**
     * Keycloak nests roles under resource_access.{client}.roles. Spring's hasRole()
     * expects a ROLE_ prefix, so it is added here.
     */
    private Converter<Jwt, Mono<AbstractAuthenticationToken>> grantedAuthoritiesExtractor() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");
            if (resourceAccess == null) {
                return Flux.empty();            // a token with no roles is not an error
            }
            Object client = resourceAccess.get(KEYCLOAK_CLIENT_ID);
            if (!(client instanceof Map<?, ?> clientMap)) {
                return Flux.empty();
            }
            Object roles = clientMap.get("roles");
            if (!(roles instanceof List<?> roleList)) {
                return Flux.empty();
            }
            return Flux.fromIterable(roleList)
                    .map(String::valueOf)
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter;
    }
}
