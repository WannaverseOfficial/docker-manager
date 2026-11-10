package com.wannaverse.dto;

import lombok.Data;

/** Optional body for /api/auth/refresh and /logout. The UI uses the HttpOnly cookie instead. */
@Data
public class RefreshTokenRequest {
    private String refreshToken;
}
