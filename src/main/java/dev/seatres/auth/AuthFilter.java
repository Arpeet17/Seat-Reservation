package dev.seatres.auth;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import dev.seatres.web.RequestContext;

/**
 * Resolves the caller from "Authorization: Bearer &lt;token&gt;" and stores the verified Principal
 * as a request attribute. Enforcement happens in {@link PrincipalArgumentResolver}: any controller
 * method that declares a Principal parameter is authenticated, everything else is public.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {

    static final String ATTR_PRINCIPAL = "seatres.principal";

    private final TokenService tokens;

    public AuthFilter(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            tokens.verify(header.substring(7).trim()).ifPresent(p -> {
                req.setAttribute(ATTR_PRINCIPAL, p);
                MDC.put(RequestContext.MDC_USER_ID, p.userId());
            });
        }
        chain.doFilter(req, res);
    }
}
