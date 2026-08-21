package com.applyflow.config;

import static org.springframework.security.config.Customizer.withDefaults;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.session.security.web.authentication.SpringSessionRememberMeServices;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.applyflow.dto.auth.CurrentUserResponse;
import com.applyflow.security.AuthenticatedUser;
import com.applyflow.service.ApplyFlowOidcUserService;
import com.applyflow.service.AuthenticationService;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
@Profile("!legacy-api-test")
public class SecurityConfig {

    private final ObjectMapper objectMapper;
    private final AuthenticationService authenticationService;
    private final ApplyFlowOidcUserService oidcUserService;
    private final String frontendUrl;
    private final AuthenticationRateLimitFilter authenticationRateLimitFilter;
    private final boolean hstsEnabled;

    public SecurityConfig(
            ObjectMapper objectMapper,
            AuthenticationService authenticationService,
            ApplyFlowOidcUserService oidcUserService,
            AuthenticationRateLimitFilter authenticationRateLimitFilter,
            @Value("${app.frontend-url}") String frontendUrl,
            @Value("${app.security.hsts-enabled:false}") boolean hstsEnabled
    ) {
        this.objectMapper = objectMapper;
        this.authenticationService = authenticationService;
        this.oidcUserService = oidcUserService;
        this.authenticationRateLimitFilter = authenticationRateLimitFilter;
        this.frontendUrl = frontendUrl.replaceAll("/$", "");
        this.hstsEnabled = hstsEnabled;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SpringSessionRememberMeServices rememberMeServices
    ) throws Exception {
        HttpSessionCsrfTokenRepository csrfRepository = new HttpSessionCsrfTokenRepository();
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
                .cors(withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfHandler))
                .addFilterBefore(authenticationRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/api/auth/csrf",
                                "/api/auth/register",
                                "/api/auth/email-verification/**",
                                "/api/auth/password/forgot",
                                "/api/auth/password/reset",
                                "/api/auth/login",
                                "/oauth2/**",
                                "/login/oauth2/**",
                                "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginProcessingUrl("/api/auth/login")
                        .usernameParameter("email")
                        .passwordParameter("password")
                        .successHandler((request, response, authentication) -> {
                            AuthenticatedUser principal = requirePrincipal(authentication.getPrincipal());
                            CurrentUserResponse currentUser = authenticationService.currentUser(principal.userId());
                            response.setStatus(HttpServletResponseStatus.OK);
                            response.setContentType("application/json");
                            objectMapper.writeValue(response.getOutputStream(), currentUser);
                        })
                        .failureHandler((request, response, exception) -> SecurityProblemWriter.write(
                                objectMapper, response, 401, "Authentication failed", "Invalid credentials")))
                .rememberMe(remember -> remember.rememberMeServices(rememberMeServices))
                .oauth2Login(oauth -> oauth
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService))
                        .successHandler((request, response, authentication) ->
                                response.sendRedirect(frontendUrl + "/auth/callback"))
                        .failureHandler((request, response, exception) ->
                                response.sendRedirect(frontendUrl + "/sign-in?oauthError=oauth_failed")))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .clearAuthentication(true)
                        .invalidateHttpSession(true)
                        .deleteCookies("APPLYFLOW_SESSION")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponseStatus.NO_CONTENT)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> SecurityProblemWriter.write(
                                objectMapper, response, 401, "Authentication required",
                                "A valid ApplyFlow session is required"))
                        .accessDeniedHandler((request, response, exception) -> SecurityProblemWriter.write(
                                objectMapper, response, 403, "Access denied",
                                "The request is not authorized")));
        if (hstsEnabled) {
            http.requiresChannel(channel -> channel.anyRequest().requiresSecure());
            http.headers(headers -> headers.httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .preload(true)
                    .maxAgeInSeconds(31_536_000)));
        }
        return http.build();
    }

    @Bean
    FilterRegistrationBean<AuthenticationRateLimitFilter> authenticationRateLimitFilterRegistration(
            AuthenticationRateLimitFilter filter
    ) {
        FilterRegistrationBean<AuthenticationRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    static PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    SpringSessionRememberMeServices rememberMeServices(
            @Value("${app.auth.remember-me}") Duration validity
    ) {
        SpringSessionRememberMeServices services = new SpringSessionRememberMeServices();
        services.setRememberMeParameterName("rememberMe");
        services.setValiditySeconds(Math.toIntExact(validity.toSeconds()));
        return services;
    }

    @Bean
    DefaultCookieSerializer cookieSerializer(
            @Value("${server.servlet.session.cookie.name}") String cookieName,
            @Value("${server.servlet.session.cookie.secure}") boolean secure,
            @Value("${server.servlet.session.cookie.same-site}") String sameSite
    ) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(cookieName);
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(secure);
        serializer.setSameSite(sameSite);
        serializer.setRememberMeRequestAttribute(SpringSessionRememberMeServices.REMEMBER_ME_LOGIN_ATTR);
        return serializer;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") String allowedOrigins
    ) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "Accept", "X-CSRF-TOKEN"));
        configuration.setExposedHeaders(List.of("Location"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private AuthenticatedUser requirePrincipal(Object principal) {
        if (principal instanceof AuthenticatedUser authenticatedUser) {
            return authenticatedUser;
        }
        throw new IllegalStateException("Authenticated principal does not contain an ApplyFlow user ID");
    }

    private static final class HttpServletResponseStatus {
        private static final int OK = 200;
        private static final int NO_CONTENT = 204;

        private HttpServletResponseStatus() {
        }
    }
}
