package com.agentic.platform.web.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentic.support.MutableClock;
import com.agentic.support.TestClockConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
        "app.rate-limit.rules[0].name=create-link",
        "app.rate-limit.rules[0].method=POST",
        "app.rate-limit.rules[0].path=/api/v1/urls",
        "app.rate-limit.rules[0].capacity=3",
        "app.rate-limit.rules[0].refill-period=1m"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class RateLimitIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    private static MockHttpServletRequestBuilder createFrom(String clientIp) {
        return post("/api/v1/urls")
                .with(request -> {
                    request.setRemoteAddr(clientIp);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\": \"https://example.com\"}");
    }

    @Test
    void fourthRequestInAMinuteIsRejectedThenRecoversAfterRefill() throws Exception {
        String ip = "203.0.113.7";
        for (int remaining = 2; remaining >= 0; remaining--) {
            mvc.perform(createFrom(ip))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("RateLimit-Limit", "3"))
                    .andExpect(header().string("RateLimit-Remaining", String.valueOf(remaining)));
        }

        mvc.perform(createFrom(ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "20"))
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"));

        clock.advance(Duration.ofSeconds(20));
        mvc.perform(createFrom(ip)).andExpect(status().isCreated());
    }

    @Test
    void clientsHaveIndependentBuckets() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(createFrom("198.51.100.1")).andExpect(status().isCreated());
        }
        mvc.perform(createFrom("198.51.100.1")).andExpect(status().isTooManyRequests());

        mvc.perform(createFrom("198.51.100.2")).andExpect(status().isCreated());
    }

    @Test
    void rulesOnlyApplyToTheirMethodAndPath() throws Exception {
        String ip = "192.0.2.99";
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/api/v1/urls/whatever")
                            .with(r -> {
                                r.setRemoteAddr(ip);
                                return r;
                            }))
                    .andExpect(status().isNotFound())
                    .andExpect(header().doesNotExist("RateLimit-Limit"));
        }
    }
}
