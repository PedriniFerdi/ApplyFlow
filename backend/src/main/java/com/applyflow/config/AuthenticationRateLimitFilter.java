package com.applyflow.config;

import java.io.IOException;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.applyflow.exception.RateLimitExceededException;
import com.applyflow.service.AuthenticationRateLimiter;
import com.applyflow.service.AuthenticationService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class AuthenticationRateLimitFilter extends OncePerRequestFilter {

    private final AntPathRequestMatcher login = new AntPathRequestMatcher("/api/auth/login", HttpMethod.POST.name());
    private final AuthenticationRateLimiter rateLimiter;

    public AuthenticationRateLimitFilter(AuthenticationRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !login.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            rateLimiter.checkLogin(request.getRemoteAddr(), AuthenticationService.normalizeEmail(request.getParameter("email")));
            chain.doFilter(request, response);
        } catch (RateLimitExceededException exception) {
            response.setHeader("Retry-After", Long.toString(exception.getRetryAfterSeconds()));
            SecurityProblemWriter.write(response, HttpStatus.TOO_MANY_REQUESTS.value(),
                    "Too many attempts", "Please try again later.");
        }
    }
}
