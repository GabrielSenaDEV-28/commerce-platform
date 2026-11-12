package com.gabrielsena.commerce.auth.config;

import com.gabrielsena.commerce.auth.api.StoreSessionController;
import com.gabrielsena.commerce.auth.api.StoreSessionCookies;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/**
 * CSRF defense for cookie-authenticated session endpoints: a custom header (forcing a CORS preflight that is never
 * granted) plus an Origin that must exactly match the requested storefront origin. Sibling subdomains are rejected.
 */
public class StoreSessionRequestGuard implements HandlerInterceptor {
    public static final String HEADER = "X-Store-Session";

    private final StoreSessionCookies cookies;

    public StoreSessionRequestGuard(StoreSessionCookies cookies) {
        this.cookies = cookies;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod method) || method.getBeanType() != StoreSessionController.class) {
            return true;
        }
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if ("1".equals(request.getHeader(HEADER)) && sameOrigin(request)
                && (fetchSite == null || "same-origin".equals(fetchSite))) {
            return true;
        }
        response.setStatus(403);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"STORE_SESSION_ORIGIN_REJECTED\"}");
        return false;
    }

    private boolean sameOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        String host = request.getServerName();
        if (origin == null || host == null) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(origin);
        } catch (Exception exception) {
            return false;
        }
        String scheme = cookies.secureRequired() ? "https" : request.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getScheme() == null || uri.getHost() == null || uri.getRawUserInfo() != null
                || !(uri.getRawPath() == null || uri.getRawPath().isEmpty()) || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !scheme.equals(uri.getScheme().toLowerCase(Locale.ROOT))
                || !host.equalsIgnoreCase(uri.getHost())) {
            return false;
        }
        return normalize(scheme, uri.getPort()) == normalize(scheme, hostHeaderPort(request));
    }

    private static int hostHeaderPort(HttpServletRequest request) {
        String hostHeader = request.getHeader(HttpHeaders.HOST);
        if (hostHeader == null) {
            return -1;
        }
        int bracket = hostHeader.lastIndexOf(']');
        int colon = hostHeader.lastIndexOf(':');
        if (colon < 0 || colon < bracket) {
            return -1;
        }
        try {
            return Integer.parseInt(hostHeader.substring(colon + 1));
        } catch (NumberFormatException exception) {
            return -2;
        }
    }

    private static int normalize(String scheme, int port) {
        int defaultPort = "https".equals(scheme) ? 443 : 80;
        return port == defaultPort ? -1 : port;
    }
}
