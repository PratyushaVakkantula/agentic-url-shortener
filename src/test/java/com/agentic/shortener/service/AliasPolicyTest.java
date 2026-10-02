package com.agentic.shortener.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentic.shortener.domain.InvalidLinkRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AliasPolicyTest {

    private final AliasPolicy policy = new AliasPolicy();

    @ParameterizedTest
    @ValueSource(strings = {"abc", "my-launch_2026", "A1b2C3", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void acceptsValidAliases(String alias) {
        assertThatCode(() -> policy.validate(alias)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "has space", "slash/es", "dot.dot", "émoji", "../etc", "%2e%2e"})
    void rejectsMalformedAliases(String alias) {
        assertThatThrownBy(() -> policy.validate(alias))
                .isInstanceOf(InvalidLinkRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_ALIAS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "API", "Actuator", "swagger-ui", "admin", "h2-console", "error"})
    void rejectsReservedWordsCaseInsensitively(String alias) {
        assertThatThrownBy(() -> policy.validate(alias))
                .isInstanceOf(InvalidLinkRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "RESERVED_ALIAS");
    }

    @Test
    void rejectsTooLongAlias() {
        assertThatThrownBy(() -> policy.validate("x".repeat(31)))
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_ALIAS");
    }
}
