package com.metabion.config;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Temporary probe controller to verify the security filter chain is wired correctly.
 * Delete this in Phase 05 when the real {@code AuthController} is introduced.
 */
@RestController
public class WhoamiController {

    @GetMapping("/api/whoami")
    public Map<String, Object> me(@AuthenticationPrincipal Object principal) {
        return Map.of("principal", principal == null ? "anonymous" : principal.toString());
    }
}