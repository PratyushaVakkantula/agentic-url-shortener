package com.agentic.platform;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformSecurityIntegrationTest {

    private static final String UUID_PATTERN = "[0-9a-f-]{36}";

    @Autowired
    MockMvc mvc;

    @Nested
    @DisplayName("public endpoints")
    class PublicEndpoints {

        @Test
        void healthIsPublic() throws Exception {
            mvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }

        @Test
        void openApiSpecIsPublic() throws Exception {
            mvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.info.title").value("Agentic URL Shortener API"))
                    .andExpect(jsonPath("$.components.securitySchemes.basicAuth.scheme").value("basic"));
        }
    }

    @Nested
    @DisplayName("authentication and authorization")
    class AuthRules {

        @Test
        void unauthenticatedRequestToProtectedRouteGets401ProblemDetail() throws Exception {
            mvc.perform(get("/api/v1/workflows"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(header().string("WWW-Authenticate", containsString("Basic")))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.instance").value("/api/v1/workflows"))
                    .andExpect(jsonPath("$.requestId").value(matchesPattern(UUID_PATTERN)));
        }

        @Test
        void wrongPasswordIsRejected() throws Exception {
            mvc.perform(get("/actuator/metrics").with(httpBasic("admin", "not-the-password")))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void nonAdminCannotReadMetrics() throws Exception {
            mvc.perform(get("/actuator/metrics").with(httpBasic("alice", "alice-pass")))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        void adminCanReadMetrics() throws Exception {
            mvc.perform(get("/actuator/metrics").with(httpBasic("admin", "admin-pass")))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("request correlation and headers")
    class Correlation {

        @Test
        void wellFormedRequestIdIsEchoed() throws Exception {
            mvc.perform(get("/actuator/health").header("X-Request-Id", "client-abc_123"))
                    .andExpect(header().string("X-Request-Id", "client-abc_123"));
        }

        @Test
        void maliciousRequestIdIsReplaced() throws Exception {
            // A newline would let a caller forge extra log lines (log injection).
            mvc.perform(get("/actuator/health").header("X-Request-Id", "evil\nFAKE LOG LINE"))
                    .andExpect(header().string("X-Request-Id", matchesPattern(UUID_PATTERN)));
        }

        @Test
        void securityHeadersArePresent() throws Exception {
            mvc.perform(get("/actuator/health"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().exists("Content-Security-Policy"));
        }
    }
}
