package com.gabrielsena.commerce.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Host-only (no Domain), path-scoped, HttpOnly, SameSite=Strict refresh cookie for browser store sessions. */
@Component
public class StoreSessionCookies {
    public static final String NAME = "store_session";
    public static final String PATH = "/api/public/store-auth/session";

    private final boolean secure;

    public StoreSessionCookies(@Value("${store-session.cookie-secure:true}") boolean secure) {
        this.secure = secure;
    }

    /** True unless explicitly disabled for plain-HTTP local development. */
    public boolean secureRequired() {
        return secure;
    }

    public String issue(HttpServletRequest request, String token, Duration maxAge) {
        return base(request, token).maxAge(maxAge).build().toString();
    }

    public String clear(HttpServletRequest request) {
        return base(request, "").maxAge(Duration.ZERO).build().toString();
    }

    private ResponseCookie.ResponseCookieBuilder base(HttpServletRequest request, String value) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(secure || request.isSecure())
                .sameSite("Strict").path(PATH);
    }
}
