package com.agentic.platform.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Demo user directory bound from {@code app.security.users}. Validated at startup so a
 * misconfigured user fails fast instead of silently locking someone out.
 */
@Validated
@ConfigurationProperties("app.security")
public record SecurityUsersProperties(@NotEmpty List<@Valid User> users) {

    public record User(@NotBlank String username, @NotBlank String password, @NotEmpty Set<Role> roles) {
    }
}
