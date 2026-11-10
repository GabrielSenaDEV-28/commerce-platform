package com.gabrielsena.commerce.auth.config;

import com.gabrielsena.commerce.auth.api.AuthController;
import com.gabrielsena.commerce.auth.api.StoreAuthController;
import com.gabrielsena.commerce.auth.application.AuthenticationRateLimiter;
import com.gabrielsena.commerce.auth.application.AuthenticationRateLimiter.Category;
import com.gabrielsena.commerce.customer.api.CustomerAuthController;
import com.gabrielsena.commerce.merchant.api.MerchantController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

public class AuthenticationRateLimitInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(AuthenticationRateLimitInterceptor.class);
    private final AuthenticationRateLimiter limiter;

    public AuthenticationRateLimitInterceptor(AuthenticationRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        Category category = category(handler);
        if (category == null) {
            return true;
        }
        String address = request.getRemoteAddr();
        if (address == null || address.isBlank() || address.length() > 45) {
            log.error("Cannot rate limit authentication: invalid remote address");
            unavailable(response);
            return false;
        }
        try {
            var decision = limiter.check(address, category);
            if (!decision.allowed()) {
                response.setStatus(429);
                response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"code\":\"AUTH_RATE_LIMITED\"}");
                return false;
            }
        } catch (DataAccessException exception) {
            log.error("Authentication rate limiter unavailable", exception);
            unavailable(response);
            return false;
        }
        return true;
    }

    private static void unavailable(HttpServletResponse response) throws IOException {
        response.setStatus(503);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"AUTH_RATE_LIMIT_UNAVAILABLE\"}");
    }

    private static Category category(Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return null;
        }
        Class<?> type = method.getBeanType();
        String name = method.getMethod().getName();
        if (type == MerchantController.class && name.equals("onboard")
                || type == CustomerAuthController.class && name.equals("register")) {
            return Category.REGISTER;
        }
        if (type == AuthController.class || type == CustomerAuthController.class || type == StoreAuthController.class) {
            return switch (name) {
                case "login" -> Category.LOGIN;
                case "refresh" -> Category.REFRESH;
                default -> null;
            };
        }
        return null;
    }
}
