package com.agentic.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClickContextTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/128.0 Safari/537.36 Edg/128.0 | Edge",
            "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/128.0 Safari/537.36 OPR/112.0 | Opera",
            "Mozilla/5.0 (Macintosh) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36 | Chrome",
            "Mozilla/5.0 (iPhone) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile Safari/604.1 | Safari",
            "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0 | Firefox",
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html) | Bot",
            "curl/8.7.1 | CLI",
            "SomethingElse/1.0 | Other",
    })
    void classifiesBrowserFamilyMostSpecificFirst(String userAgent, String family) {
        assertThat(ClickContext.userAgentFamily(userAgent)).isEqualTo(family);
    }

    @Test
    void missingUserAgentIsUnknown() {
        assertThat(ClickContext.userAgentFamily(null)).isEqualTo("Unknown");
        assertThat(ClickContext.userAgentFamily(" ")).isEqualTo("Unknown");
    }

    @Test
    void referrerKeepsOnlyTheHost() {
        // The path/query can carry personal data (search terms, emails, tokens), so it is dropped.
        assertThat(ClickContext.referrerHost("https://News.Example.com/article?user=alice@example.com"))
                .isEqualTo("news.example.com");
        assertThat(ClickContext.referrerHost(null)).isNull();
        assertThat(ClickContext.referrerHost("not a url")).isNull();
        assertThat(ClickContext.referrerHost("android-app://com.example")).isEqualTo("com.example");
    }
}
