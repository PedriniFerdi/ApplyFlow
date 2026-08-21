package com.applyflow.service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.applyflow.repository.UserAccountRepository;
import com.applyflow.security.PasswordUserPrincipal;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserAccountRepository userRepository;

    public DatabaseUserDetailsService(UserAccountRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmailIgnoreCase(AuthenticationService.normalizeEmail(email))
                .map(PasswordUserPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}
