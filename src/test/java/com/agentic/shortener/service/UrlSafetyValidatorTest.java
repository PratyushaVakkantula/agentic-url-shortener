package com.agentic.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentic.shortener.domain.InvalidLinkRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class UrlSafetyValidatorTest {

    private final UrlSafetyValidator validator = new UrlSafetyValidator();

    @ParameterizedTest(name = "accepts {0}")
    @ValueSource(strings = {
            "https://example.com",
            "http://example.com/path?q=1&r=2#fragment",
            "HTTPS://EXAMPLE.COM/Mixed/Case",
            "https://sub.example.co.uk:8443/x",
            "https://xn--bcher-kva.example/",            // IDN in punycode form
            "https://8.8.8.8/",                          // public IPv4 literal
            "https://[2606:4700:4700::1111]/"             // public IPv6 literal
    })
    void acceptsPublicHttpUrls(String url) {
        assertThat(validator.validate(url)).isEqualTo(url);
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(validator.validate("  https://example.com  ")).isEqualTo("https://example.com");
    }

    @Test
    void acceptsUrlAtMaxLengthAndRejectsOneOver() {
        String prefix = "https://example.com/";
        String atMax = prefix + "a".repeat(UrlSafetyValidator.MAX_LENGTH - prefix.length());
        assertThat(validator.validate(atMax)).hasSize(UrlSafetyValidator.MAX_LENGTH);
        assertRejected(atMax + "a", "URL_TOO_LONG");
    }

    @ParameterizedTest(name = "[{1}] {0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            // malformed
            "\"\"                               | INVALID_URL",
            "\"   \"                            | INVALID_URL",
            "https://exa mple.com               | INVALID_URL",
            "https://                           | INVALID_URL",
            "https://bücher.example             | INVALID_URL",   // raw IDN: java.net.URI exposes no host
            "http://999.1.1.1                   | INVALID_URL",
            "http://127.1                       | INVALID_URL",   // java.net.URI rejects; defense in depth
            "http://0x7f.0.0.1                  | INVALID_URL",
            // scheme allow-list
            "javascript:alert(1)                | UNSUPPORTED_SCHEME",
            "JaVaScRiPt:alert(1)                | UNSUPPORTED_SCHEME",
            "data:text/html;base64,PHNjcmlwdD4= | UNSUPPORTED_SCHEME",
            "file:///etc/passwd                 | UNSUPPORTED_SCHEME",
            "ftp://example.com/file             | UNSUPPORTED_SCHEME",
            "example.com                        | UNSUPPORTED_SCHEME",   // no scheme at all
            "/relative/path                     | UNSUPPORTED_SCHEME",
            // deceptive credentials
            "https://google.com@evil.example    | CREDENTIALS_IN_URL",
            "https://user:pass@example.com      | CREDENTIALS_IN_URL",
            // local names
            "http://localhost                   | BLOCKED_HOST",
            "http://LOCALHOST:8080/admin        | BLOCKED_HOST",
            "http://localhost./                 | BLOCKED_HOST",   // fully-qualified form
            "http://app.localhost               | BLOCKED_HOST",
            "http://printer.local               | BLOCKED_HOST",
            "http://db.internal                 | BLOCKED_HOST",
            "http://intranet/                   | BLOCKED_HOST",   // single-label name
            // private / reserved IPv4, including alternate spellings that reach our parser
            "http://127.0.0.1                   | BLOCKED_HOST",
            "http://2130706433                  | BLOCKED_HOST",   // 127.0.0.1 as one integer
            "http://0x7f000001                  | BLOCKED_HOST",   // ... as hex
            "http://0177.0.0.1                  | BLOCKED_HOST",   // ... with octal first octet
            "http://10.0.0.5                    | BLOCKED_HOST",
            "http://172.16.3.4                  | BLOCKED_HOST",
            "http://192.168.1.1                 | BLOCKED_HOST",
            "http://169.254.169.254/latest/meta-data | BLOCKED_HOST",   // cloud metadata
            "http://100.64.0.1                  | BLOCKED_HOST",
            "http://0.0.0.0                     | BLOCKED_HOST",
            "http://224.0.0.1                   | BLOCKED_HOST",
            "http://255.255.255.255             | BLOCKED_HOST",
            // private / reserved IPv6
            "http://[::1]/                      | BLOCKED_HOST",
            "http://[::]/                       | BLOCKED_HOST",
            "http://[::ffff:127.0.0.1]/         | BLOCKED_HOST",   // IPv4-mapped
            "http://[::127.0.0.1]/              | BLOCKED_HOST",   // IPv4-compatible
            "http://[64:ff9b::a00:1]/           | BLOCKED_HOST",   // NAT64 of 10.0.0.1
            "http://[fc00::1]/                  | BLOCKED_HOST",   // unique local
            "http://[fe80::1]/                  | BLOCKED_HOST",   // link-local
            "http://[2001:db8::1]/              | BLOCKED_HOST",   // documentation
    })
    void rejectsUnsafeOrMalformedUrls(String url, String expectedCode) {
        assertRejected(url, expectedCode);
    }

    @Test
    void rejectsNullAndControlCharacters() {
        assertRejected(null, "INVALID_URL");
        assertRejected("https://example.com/\u0000", "INVALID_URL");
        assertRejected("https://example.com/\nSet-Cookie:x", "INVALID_URL");
    }

    @Test
    void parsesInetAtonForms() {
        int loopback = 0x7f000001;
        assertThat(UrlSafetyValidator.parseIpv4("127.0.0.1")).isEqualTo(loopback);
        assertThat(UrlSafetyValidator.parseIpv4("127.1")).isEqualTo(loopback);
        assertThat(UrlSafetyValidator.parseIpv4("127.0.1")).isEqualTo(loopback);
        assertThat(UrlSafetyValidator.parseIpv4("2130706433")).isEqualTo(loopback);
        assertThat(UrlSafetyValidator.parseIpv4("0x7f.0.0.1")).isEqualTo(loopback);
        assertThat(UrlSafetyValidator.parseIpv4("0177.0.0.01")).isEqualTo(loopback);
        assertThat(UrlSafetyValidator.parseIpv4("256.0.0.1")).isNull();
        assertThat(UrlSafetyValidator.parseIpv4("1.2.3.4.5")).isNull();
        assertThat(UrlSafetyValidator.parseIpv4("08.0.0.1")).isNull();   // invalid octal
        assertThat(UrlSafetyValidator.parseIpv4("4294967296")).isNull(); // > 32 bits
    }

    private void assertRejected(String url, String expectedCode) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(InvalidLinkRequestException.class)
                .satisfies(e -> {
                    InvalidLinkRequestException ex = (InvalidLinkRequestException) e;
                    assertThat(ex.errorCode()).isEqualTo(expectedCode);
                    assertThat(ex.field()).isEqualTo("url");
                });
    }
}
