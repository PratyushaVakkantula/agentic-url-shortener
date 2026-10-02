package com.agentic.shortener.api;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.shortener.service.ClickRecorder;
import com.agentic.support.MutableClock;
import com.agentic.support.TestClockConfig;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class ShortenerApiIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    ClickRecorder clickRecorder;

    @BeforeEach
    void resetClock() {
        clock.set(TestClockConfig.T0);
    }

    private ResultActions create(String json) throws Exception {
        return mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private void createAlias(String alias, String extraJson) throws Exception {
        create("""
                {"url": "https://example.com/%s", "customAlias": "%s"%s}""".formatted(alias, alias, extraJson))
                .andExpect(status().isCreated());
    }

    @Nested
    @DisplayName("POST /api/v1/urls")
    class Create {

        @Test
        void createsLinkWithGeneratedCode() throws Exception {
            create("""
                    {"url": "https://example.com/a/very/long/path?with=query"}""")
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", matchesPattern("/api/v1/urls/[0-9A-Za-z]{7}")))
                    .andExpect(jsonPath("$.code").value(matchesPattern("[0-9A-Za-z]{7}")))
                    .andExpect(jsonPath("$.shortUrl").value(startsWith("http://localhost:8080/")))
                    .andExpect(jsonPath("$.originalUrl").value("https://example.com/a/very/long/path?with=query"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.customAlias").value(false))
                    .andExpect(jsonPath("$.createdAt").value("2026-03-10T09:00:00Z"))
                    .andExpect(header().exists("RateLimit-Remaining"));
        }

        @Test
        void createsLinkWithAliasAndExpiry() throws Exception {
            create("""
                    {"url": "https://example.com", "customAlias": "spring-sale", "expiresAt": "2026-04-01T00:00:00Z"}""")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("spring-sale"))
                    .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/spring-sale"))
                    .andExpect(jsonPath("$.expiresAt").value("2026-04-01T00:00:00Z"));
        }

        @Test
        void unsafeUrlIsRejectedWithSpecificErrorCode() throws Exception {
            create("""
                    {"url": "http://169.254.169.254/latest/meta-data"}""")
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.errorCode").value("BLOCKED_HOST"))
                    .andExpect(jsonPath("$.field").value("url"))
                    .andExpect(jsonPath("$.requestId").value(notNullValue()));
        }

        @Test
        void missingUrlListsFieldErrors() throws Exception {
            create("{}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("url"));
        }

        @Test
        void malformedJsonDoesNotEchoParserDetails() throws Exception {
            create("{\"url\": ")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.detail").value("Request body is missing or not valid JSON."));
        }

        @Test
        void takenAliasConflicts() throws Exception {
            createAlias("taken-one", "");
            create("""
                    {"url": "https://other.example", "customAlias": "taken-one"}""")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("ALIAS_TAKEN"));
        }

        @Test
        void reservedAliasIsRejected() throws Exception {
            create("""
                    {"url": "https://example.com", "customAlias": "Actuator"}""")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("RESERVED_ALIAS"));
        }
    }

    @Nested
    @DisplayName("GET /{code} redirect")
    class Redirect {

        @Test
        void redirectsWith302AndNoStore() throws Exception {
            createAlias("go-here", "");
            mvc.perform(get("/go-here"))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "https://example.com/go-here"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }

        @Test
        void unknownCodeIs404Problem() throws Exception {
            mvc.perform(get("/nope-nope"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("LINK_NOT_FOUND"));
        }

        @Test
        void cachedLinkStillStopsRedirectingTheMomentItExpires() throws Exception {
            createAlias("short-lived", ", \"expiresAt\": \"2026-03-10T10:00:00Z\"");
            mvc.perform(get("/short-lived")).andExpect(status().isFound()); // now cached

            clock.advance(Duration.ofHours(1));

            mvc.perform(get("/short-lived"))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.errorCode").value("LINK_EXPIRED"));
        }

        @Test
        void pathsWithDotsAreNotTreatedAsCodes() throws Exception {
            mvc.perform(get("/favicon.ico"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/urls/{code}")
    class Deactivate {

        @Test
        void requiresAdmin() throws Exception {
            createAlias("guarded", "");
            mvc.perform(delete("/api/v1/urls/guarded"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(delete("/api/v1/urls/guarded").with(httpBasic("alice", "alice-pass")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        }

        @Test
        void deactivationEvictsCacheAndIsIdempotent() throws Exception {
            createAlias("retire-me", "");
            mvc.perform(get("/retire-me")).andExpect(status().isFound()); // now cached

            mvc.perform(delete("/api/v1/urls/retire-me").with(httpBasic("admin", "admin-pass")))
                    .andExpect(status().isNoContent());
            mvc.perform(delete("/api/v1/urls/retire-me").with(httpBasic("admin", "admin-pass")))
                    .andExpect(status().isNoContent());

            mvc.perform(get("/retire-me"))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.errorCode").value("LINK_DEACTIVATED"));
            mvc.perform(get("/api/v1/urls/retire-me"))
                    .andExpect(jsonPath("$.status").value("DEACTIVATED"))
                    .andExpect(jsonPath("$.deactivatedAt").value("2026-03-10T09:00:00Z"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/urls/{code}/analytics")
    class Analytics {

        @Test
        void aggregatesClicksPerDayReferrerAndBrowser() throws Exception {
            createAlias("tracked", "");
            String chrome = "Mozilla/5.0 (Macintosh) AppleWebKit/537.36 Chrome/128.0 Safari/537.36";
            String firefox = "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0";

            mvc.perform(get("/tracked").header("Referer", "https://news.example.com/post?id=1").header("User-Agent", chrome));
            mvc.perform(get("/tracked").header("Referer", "https://news.example.com/other").header("User-Agent", chrome));
            mvc.perform(get("/tracked").header("Referer", "https://social.example/feed").header("User-Agent", firefox));
            clock.advance(Duration.ofDays(1));
            mvc.perform(get("/tracked").header("User-Agent", firefox));
            clickRecorder.flush();

            mvc.perform(get("/api/v1/urls/tracked/analytics").param("days", "7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalClicks").value(4))
                    .andExpect(jsonPath("$.lastAccessedAt").value("2026-03-11T09:00:00Z"))
                    .andExpect(jsonPath("$.windowStart").value("2026-03-05"))
                    .andExpect(jsonPath("$.windowEnd").value("2026-03-11"))
                    .andExpect(jsonPath("$.clicksPerDay", hasSize(7)))
                    .andExpect(jsonPath("$.clicksPerDay[0].clicks").value(0))
                    .andExpect(jsonPath("$.clicksPerDay[5].date").value("2026-03-10"))
                    .andExpect(jsonPath("$.clicksPerDay[5].clicks").value(3))
                    .andExpect(jsonPath("$.clicksPerDay[6].clicks").value(1))
                    .andExpect(jsonPath("$.topReferrers[0].name").value("news.example.com"))
                    .andExpect(jsonPath("$.topReferrers[0].clicks").value(2))
                    .andExpect(jsonPath("$.topReferrers", hasSize(2)))
                    .andExpect(jsonPath("$.browsers[0].name").value("Chrome"))
                    .andExpect(jsonPath("$.browsers[0].clicks").value(2));
        }

        /**
         * Regression: clicks at 00:30Z and 23:30Z are on the same UTC day. Bucketing in the
         * server's local zone (the bug found in the live demo) splits them across two days.
         */
        @Test
        void bucketsDaysInUtcRegardlessOfServerTimeZone() throws Exception {
            createAlias("utc-days", "");
            clock.set(java.time.Instant.parse("2026-03-10T00:30:00Z"));
            mvc.perform(get("/utc-days"));
            clock.set(java.time.Instant.parse("2026-03-10T23:30:00Z"));
            mvc.perform(get("/utc-days"));
            clickRecorder.flush();

            mvc.perform(get("/api/v1/urls/utc-days/analytics").param("days", "2"))
                    .andExpect(jsonPath("$.clicksPerDay[1].date").value("2026-03-10"))
                    .andExpect(jsonPath("$.clicksPerDay[1].clicks").value(2))
                    .andExpect(jsonPath("$.clicksPerDay[0].clicks").value(0));
        }

        @Test
        void rejectsOutOfRangeWindow() throws Exception {
            createAlias("window-check", "");
            mvc.perform(get("/api/v1/urls/window-check/analytics").param("days", "0"))
                    .andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/urls/window-check/analytics").param("days", "366"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void unknownLinkIs404() throws Exception {
            mvc.perform(get("/api/v1/urls/missing-link/analytics"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.instance").value(endsWith("/analytics")));
        }
    }
}
