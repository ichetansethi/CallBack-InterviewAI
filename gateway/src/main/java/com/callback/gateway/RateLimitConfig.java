package com.callback.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

@Configuration
public class RateLimitConfig {

    /**
     * Rate-limit bucket per authenticated user (the email ReactiveJwtAuthenticationFilter puts in
     * the Reactor context), so one user's burst never eats another user's quota. Read from
     * ReactiveSecurityContextHolder, not exchange.getPrincipal(): the filter only does
     * contextWrite, so the exchange's principal stays empty.
     *
     * <p>Unauthenticated requests (login/register, or a bad token) fall back to a per-IP bucket
     * rather than an empty key, which the limiter would turn into a 403 for every caller.
     */
    @Bean
    KeyResolver userKeyResolver() {
        return exchange -> ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .map(auth -> "user:" + auth.getName())
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
                    return "ip:" + (remote != null ? remote.getAddress().getHostAddress() : "unknown");
                }));
    }

}
