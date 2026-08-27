package com.applyflow.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.applyflow.exception.RateLimitExceededException;
import com.applyflow.service.AuthenticationRateLimiter;
import com.applyflow.service.AuthenticationService;
import com.applyflow.security.TrustedClientIpResolver;
import com.applyflow.validation.RequestLimits;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class AuthenticationRateLimitFilter extends OncePerRequestFilter {

    private final AntPathRequestMatcher login = new AntPathRequestMatcher("/api/auth/login", HttpMethod.POST.name());
    private final AuthenticationRateLimiter rateLimiter;
    private final TrustedClientIpResolver clientIpResolver;

    public AuthenticationRateLimitFilter(AuthenticationRateLimiter rateLimiter, TrustedClientIpResolver clientIpResolver) {
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !login.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String password = request.getParameter("password");
        if (RequestLimits.exceedsCodePoints(request.getParameter("email"), RequestLimits.EMAIL)
                || RequestLimits.exceedsCodePoints(password, RequestLimits.PASSWORD)
                || password != null && password.getBytes(StandardCharsets.UTF_8).length > RequestLimits.PASSWORD) {
            SecurityProblemWriter.write(response, HttpStatus.BAD_REQUEST.value(),
                    "Validation failed", "One or more login fields are invalid");
            return;
        }
        try {
            rateLimiter.checkLogin(clientIpResolver.resolve(request), AuthenticationService.normalizeEmail(request.getParameter("email")));
            chain.doFilter(request, response);
        } catch (RateLimitExceededException exception) {
            response.setHeader("Retry-After", Long.toString(exception.getRetryAfterSeconds()));
            SecurityProblemWriter.write(response, HttpStatus.TOO_MANY_REQUESTS.value(),
                    "Too many attempts", "Please try again later.");
        }
    }
}
