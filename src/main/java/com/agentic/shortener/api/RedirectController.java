package com.agentic.shortener.api;

import com.agentic.shortener.service.RedirectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Redirect")
public class RedirectController {

    private final RedirectService redirects;

    public RedirectController(RedirectService redirects) {
        this.redirects = redirects;
    }

    /**
     * 302 + no-store (A-2): a 301, or a cacheable 302, would let browsers skip us on repeat
     * visits, so clicks would be undercounted and deactivation would not take effect for them.
     * The path pattern excludes dots, so routes like /swagger-ui.html or /favicon.ico never
     * reach this handler.
     */
    @GetMapping("/{code:[A-Za-z0-9_-]{3,30}}")
    @Operation(summary = "Follow a short link", description = "302 to the target; 404 unknown; 410 expired or deactivated.")
    public ResponseEntity<Void> redirect(@PathVariable String code,
                                         @RequestHeader(value = HttpHeaders.REFERER, required = false) String referer,
                                         @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        String target = redirects.resolve(code, referer, userAgent);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
