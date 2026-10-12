package com.amazeawn.api.auth;

import com.amazeawn.api.config.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

public final class CurrentUser {
    private CurrentUser() {}

    public static JwtService.Principal principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof JwtService.Principal p) {
            return p;
        }
        throw ApiException.unauthorized("Not authenticated");
    }

    public static UUID id() {
        return principal().userId();
    }
}
