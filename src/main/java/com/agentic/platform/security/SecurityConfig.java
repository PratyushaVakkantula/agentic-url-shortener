package com.agentic.platform.security;

import com.agentic.platform.web.ProblemDetailsAuthHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP security. Deny by default: every route not listed as public needs authentication,
 * and fine-grained role checks live next to the endpoints via {@code @PreAuthorize}.
 *
 * <p>CSRF is disabled on purpose: the API is stateless (no session, no cookies) and
 * authenticates every request with an Authorization header, which browsers do not attach
 * automatically, so cross-site request forgery does not apply.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemDetailsAuthHandler authHandler) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.authenticationEntryPoint(authHandler))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authHandler)
                        .accessDeniedHandler(authHandler))
                .authorizeHttpRequests(auth -> auth
                        // Operational + API docs
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/**").hasRole(Role.ADMIN.name())
                        // Shortener: creating and reading links is public; deactivation is not.
                        .requestMatchers(HttpMethod.POST, "/api/v1/urls").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/urls/**").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/urls/**").hasRole(Role.ADMIN.name())
                        // Redirects: single path segment, e.g. GET /aZ3kQ9x
                        .requestMatchers(HttpMethod.GET, "/*").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:")));
        return http.build();
    }

    /** ADMIN implies every other role; keeps @PreAuthorize expressions simple. */
    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role(Role.ADMIN.name()).implies(Role.APPROVER.name(), Role.REQUESTER.name())
                .build();
    }

    @Bean
    UserDetailsService userDetailsService(SecurityUsersProperties props) {
        var users = props.users().stream()
                .map(u -> User.withUsername(u.username())
                        .password(u.password())
                        .roles(u.roles().stream().map(Role::name).toArray(String[]::new))
                        .build())
                .toList();
        return new InMemoryUserDetailsManager(users);
    }

    /**
     * Delegating encoder: reads the {@code {bcrypt}} prefix stored with each hash, so the
     * hashing algorithm can be upgraded later without invalidating existing credentials.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
