package com.applyflow.security;

import java.io.Serial;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import com.applyflow.entity.UserAccount;

public final class ApplyFlowOidcPrincipal implements OidcUser, AuthenticatedUser {

    @Serial
    private static final long serialVersionUID = 1L;

    private final OidcUser delegate;
    private final Long userId;
    private final String email;
    private final Set<GrantedAuthority> authorities;

    public ApplyFlowOidcPrincipal(OidcUser delegate, UserAccount user) {
        this.delegate = delegate;
        this.userId = user.getId();
        this.email = user.getEmail();
        this.authorities = new LinkedHashSet<>(delegate.getAuthorities());
        this.authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
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
    public Map<String, Object> getClaims() {
        return delegate.getClaims();
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return delegate.getUserInfo();
    }

    @Override
    public OidcIdToken getIdToken() {
        return delegate.getIdToken();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return delegate.getAttributes();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Set.copyOf(authorities);
    }

    @Override
    public String getName() {
        return email;
    }
}
