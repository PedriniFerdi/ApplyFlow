package com.applyflow.config;

import java.io.IOException;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import com.applyflow.repository.UserAccountRepository;
import com.applyflow.security.AuthenticatedUser;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

final class ActiveAccountFilter extends OncePerRequestFilter {

    private final UserAccountRepository userRepository;
    private final SecurityContextLogoutHandler logout = new SecurityContextLogoutHandler();

    ActiveAccountFilter(UserAccountRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser principal
                && !userRepository.existsById(principal.userId())) {
            logout.logout(request, response, authentication);
            SecurityProblemWriter.write(response, 401, "Authentication required", "A valid ApplyFlow session is required");
            return;
        }
        chain.doFilter(request, response);
    }
}
