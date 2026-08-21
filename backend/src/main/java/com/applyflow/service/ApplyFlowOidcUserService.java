package com.applyflow.service;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.stereotype.Service;

import com.applyflow.entity.UserAccount;
import com.applyflow.security.ApplyFlowOidcPrincipal;

@Service
public class ApplyFlowOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OidcUserService delegate = new OidcUserService();
    private final OidcAccountService accountService;

    public ApplyFlowOidcUserService(OidcAccountService accountService) {
        this.accountService = accountService;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) {
        OidcUser oidcUser = delegate.loadUser(userRequest);
        UserAccount account = accountService.reconcile(
                oidcUser.getSubject(),
                oidcUser.getEmail(),
                Boolean.TRUE.equals(oidcUser.getEmailVerified()),
                oidcUser.getFullName());
        return new ApplyFlowOidcPrincipal(oidcUser, account);
    }
}
