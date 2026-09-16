package com.callback.security.jwt;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Collections;

/**
 * Reactive twin of JwtAuthenticationFilter, for WebFlux-only services (no jakarta.servlet-api on
 * the runtime classpath, so the servlet-based filter can't run there). Reuses JwtValidator as-is —
 * signature/expiry verification and email extraction are identical; only the wrapper differs.
 *
 * <p>Checks the "token" query parameter before the Authorization header: a WebSocket upgrade
 * request starts as a plain HTTP GET that a browser cannot attach a custom header to, so the token
 * travels as a query param instead. A plain reactive REST endpoint the service also exposes still
 * authenticates via the Authorization header as normal.
 *
 * <p>Guarded by {@code @ConditionalOnWebApplication(REACTIVE)} rather than
 * {@code @ConditionalOnClass}, unlike JwtAuthenticationFilter's guard — deliberately, not by
 * oversight. The obvious class-presence check here would be something in spring-webflux (e.g.
 * {@code DispatcherHandler}), but that jar turns out to be on every Spring-AI-using service's
 * classpath regardless of servlet vs. reactive (spring-ai's Ollama/OpenAI starters pull it in
 * transitively for their own WebClient usage — confirmed via {@code mvn dependency:tree} on
 * question-service, a purely servlet service). A class-presence check would therefore register
 * this bean, harmlessly but pointlessly, in every servlet service that also happens to call an AI
 * model. {@code @ConditionalOnWebApplication(REACTIVE)} avoids that and has been confirmed correct
 * against a live servlet service ("not a reactive web application"). Its one known blind spot,
 * mirroring the bug this fixed on the servlet side: a {@code @SpringBootTest(webEnvironment =
 * NONE)} test in a reactive service that needs this bean to exist would find it missing. No such
 * test exists yet — if one is ever added, this condition is the first thing to revisit.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ReactiveJwtAuthenticationFilter implements WebFilter {

    private final JwtValidator jwtValidator;

    public ReactiveJwtAuthenticationFilter(JwtValidator jwtValidator) {
        this.jwtValidator = jwtValidator;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String token = extractToken(exchange);

        if (token != null && jwtValidator.isValid(token)) {
            String email = jwtValidator.extractEmail(token);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(email, null, Collections.emptyList());
            return chain.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
        }
        return chain.filter(exchange); // let it through unauthenticated; reject downstream if needed
    }

    private String extractToken(ServerWebExchange exchange) {
        String queryToken = exchange.getRequest().getQueryParams().getFirst("token");
        if (queryToken != null && !queryToken.isBlank()) {
            return queryToken;
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

}
