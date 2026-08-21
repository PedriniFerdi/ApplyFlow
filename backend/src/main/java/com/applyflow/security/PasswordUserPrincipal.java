package com.applyflow.security;

import java.io.Serial;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.applyflow.entity.UserAccount;

public final class PasswordUserPrincipal implements UserDetails, AuthenticatedUser {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long userId;
    private final String email;
    private final String passwordHash;
    private final boolean emailVerified;
    private final boolean hasPassword;

    public PasswordUserPrincipal(UserAccount user) {
        this.userId = user.getId();
        this.email = user.getEmail();
        this.passwordHash = user.getPasswordHash() == null ? "{noop}credential-not-configured" : user.getPasswordHash();
        this.emailVerified = user.isEmailVerified();
        this.hasPassword = user.hasPassword();
    }

    @Override
    public Long userId() {
        return userId;
    }

    @Override
    public String email() {
        return email;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return hasPassword;
    }

    @Override
    public boolean isEnabled() {
        return emailVerified;
    }
}
