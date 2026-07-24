package com.training.apigatewayservice.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.training.apigatewayservice.auth.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Applies to every gateway-proxied route (product/order/notification-service);
 * it never sees requests handled by this app's own controllers (e.g. /api/v1/auth/**),
 * since those are matched by WebFlux's normal handler mapping before Gateway routing
 * even runs.
 */
@Component
public class JwtAuthenticationGlobalFilter implements GlobalFilter, Ordered {

    private enum AccessLevel { PUBLIC, ANY_AUTHENTICATED, ADMIN_ONLY }

    private record RouteRule(HttpMethod method, String pattern, AccessLevel level) {
        boolean matches(HttpMethod requestMethod, String path, AntPathMatcher matcher) {
            return method.equals(requestMethod) && matcher.match(pattern, path);
        }
    }

    private static final List<RouteRule> RULES = List.of(
            // Product catalog browsing is public.
            new RouteRule(HttpMethod.GET, "/api/v1/products/**", AccessLevel.PUBLIC),

            // Product management is admin-only.
            new RouteRule(HttpMethod.POST, "/api/v1/products", AccessLevel.ADMIN_ONLY),
            new RouteRule(HttpMethod.PUT, "/api/v1/products/update/**", AccessLevel.ADMIN_ONLY),
            new RouteRule(HttpMethod.PATCH, "/api/v1/products/**", AccessLevel.ADMIN_ONLY),
            new RouteRule(HttpMethod.DELETE, "/api/v1/products/**", AccessLevel.ADMIN_ONLY),

            // Changing status and hard-delete are admin-only.
            new RouteRule(HttpMethod.PATCH, "/api/v1/orders/*/status", AccessLevel.ADMIN_ONLY),
            new RouteRule(HttpMethod.DELETE, "/api/v1/orders/**", AccessLevel.ADMIN_ONLY),

            // Checkout, listing, and self-service on a single order: any signed-in user.
            // order-service itself scopes GET /api/v1/orders to the caller's own
            // customerId unless the caller is an admin - the gateway only checks
            // "is this caller authenticated at all", not row-level ownership.
            new RouteRule(HttpMethod.GET, "/api/v1/orders", AccessLevel.ANY_AUTHENTICATED),
            new RouteRule(HttpMethod.POST, "/api/v1/orders", AccessLevel.ANY_AUTHENTICATED),
            new RouteRule(HttpMethod.GET, "/api/v1/orders/*", AccessLevel.ANY_AUTHENTICATED),
            new RouteRule(HttpMethod.POST, "/api/v1/orders/*/cancel", AccessLevel.ANY_AUTHENTICATED)
    );

    private final JwtService jwtService;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwtAuthenticationGlobalFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        HttpMethod method = request.getMethod();
        String path = request.getPath().value();

        AccessLevel level = RULES.stream()
                .filter(rule -> rule.matches(method, path, pathMatcher))
                .map(RouteRule::level)
                .findFirst()
                .orElse(AccessLevel.ANY_AUTHENTICATED);

        // Strip any client-supplied identity headers so they can't be spoofed;
        // the gateway is the only party allowed to set these downstream.
        ServerHttpRequest.Builder strippedBuilder = request.mutate()
                .headers(headers -> {
                    headers.remove("X-Customer-Id");
                    headers.remove("X-User-Role");
                    headers.remove("X-User-Email");
                });

        if (level == AccessLevel.PUBLIC) {
            return chain.filter(exchange.mutate().request(strippedBuilder.build()).build());
        }

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return respondWithError(exchange, HttpStatus.UNAUTHORIZED, "Missing or invalid Authorization header");
        }

        Claims claims;
        try {
            claims = jwtService.parseAndValidate(authHeader.substring("Bearer ".length()));
        } catch (JwtException | IllegalArgumentException ex) {
            return respondWithError(exchange, HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }

        String role = claims.get("role", String.class);
        if (level == AccessLevel.ADMIN_ONLY && !"ADMIN".equals(role)) {
            return respondWithError(exchange, HttpStatus.FORBIDDEN, "Admin role required");
        }

        Number customerId = claims.get("customerId", Number.class);
        ServerHttpRequest mutatedRequest = strippedBuilder
                .header("X-Customer-Id", String.valueOf(customerId))
                .header("X-User-Role", role)
                .header("X-User-Email", claims.getSubject())
                .build();
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private Mono<Void> respondWithError(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(new ErrorBody(message));
        } catch (Exception ex) {
            body = ("{\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    private record ErrorBody(String message) {
    }
}
