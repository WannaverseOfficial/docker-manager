package com.wannaverse.security;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SecurityContext {
    private final String userId;
    private final String username;
    private final boolean admin;
    private final boolean mustChangePassword;

    public boolean isAuthenticated() {
        return userId != null;
    }
}
