package com.callback.security.jwt;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * Shared across every service: trusts the RS256 signature alone, no DB lookup. A service that
 * only holds the public key has no users table to check against anyway — the token's own
 * signature and expiry are the full authority here.
 *
 * <p>{@code @ConditionalOnClass(Filter.class)} matters beyond just "don't register a useless
 * bean": Spring's classpath scanner evaluates this condition from ASM-read annotation metadata
 * before ever loading this class, so on a reactive-only service (no jakarta.servlet-api on the
 * runtime classpath) the JVM never attempts to load OncePerRequestFilter's supertype at all. That
 * makes it safe for a reactive service to broadly component-scan com.callback like every servlet
 * service already does, instead of relying on a narrower scan as the only thing preventing a
 * classloading crash.
 *
 * <p>Deliberately {@code @ConditionalOnClass}, not {@code @ConditionalOnWebApplication(SERVLET)}:
 * the latter looked more semantically direct but broke {@code ResumeTextExtractionTest}
 * (jd-resume-service), which loads the full {@code @SpringBootTest} context with
 * {@code webEnvironment = NONE} — a context Spring doesn't consider a "servlet web application"
 * even though jakarta.servlet-api is genuinely on its classpath and this bean is required
 * elsewhere in that context. Class presence is what actually determines whether this can safely
 * load; "current web application type" is a different, narrower question that a NONE-environment
 * test correctly answers "neither" to. See ReactiveJwtAuthenticationFilter for the reactive
 * counterpart, and its Javadoc for why that one couldn't use the same fix.
 */
@Component
@ConditionalOnClass(Filter.class)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtValidator jwtValidator;

    public JwtAuthenticationFilter(JwtValidator jwtValidator) {
        this.jwtValidator = jwtValidator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        if (jwtValidator.isValid(token) && SecurityContextHolder.getContext().getAuthentication() == null) {
            String email = jwtValidator.extractEmail(token);

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(email, null, Collections.emptyList());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}
