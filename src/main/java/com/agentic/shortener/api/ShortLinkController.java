package com.agentic.shortener.api;

import com.agentic.shortener.domain.ShortLink;
import com.agentic.shortener.service.AnalyticsService;
import com.agentic.shortener.service.CreateLinkCommand;
import com.agentic.shortener.service.ShortLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.Clock;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/urls")
@Tag(name = "Short links", description = "Create, inspect and deactivate short links")
public class ShortLinkController {

    private final ShortLinkService links;
    private final AnalyticsService analytics;
    private final Clock clock;

    public ShortLinkController(ShortLinkService links, AnalyticsService analytics, Clock clock) {
        this.links = links;
        this.analytics = analytics;
        this.clock = clock;
    }

    @PostMapping
    @Operation(summary = "Create a short link",
            description = "Public. Rate limited per client. Errors: 400 (validation/unsafe URL), 409 (alias taken), 429.")
    public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest request) {
        ShortLink link = links.create(new CreateLinkCommand(request.url(), request.customAlias(), request.expiresAt()));
        return ResponseEntity.created(URI.create("/api/v1/urls/" + link.getCode())).body(toResponse(link));
    }

    @GetMapping("/{code}")
    @Operation(summary = "Get link metadata")
    public LinkResponse get(@PathVariable String code) {
        return toResponse(links.get(code));
    }

    @GetMapping("/{code}/analytics")
    @Operation(summary = "Click analytics", description = "Lifetime total plus per-day, referrer and browser breakdowns for the last N UTC days.")
    public AnalyticsResponse analytics(@PathVariable String code,
                                       @RequestParam(defaultValue = "30") @Min(1) @Max(AnalyticsService.MAX_WINDOW_DAYS) int days) {
        return AnalyticsResponse.of(analytics.analytics(code, days));
    }

    @DeleteMapping("/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "basicAuth")
    @Operation(summary = "Deactivate a link (ADMIN)", description = "Soft delete; idempotent. Redirects then answer 410 Gone.")
    public ResponseEntity<Void> deactivate(@PathVariable String code) {
        links.deactivate(code);
        return ResponseEntity.noContent().build();
    }

    private LinkResponse toResponse(ShortLink link) {
        return LinkResponse.of(link, links.shortUrl(link.getCode()), clock.instant());
    }
}
