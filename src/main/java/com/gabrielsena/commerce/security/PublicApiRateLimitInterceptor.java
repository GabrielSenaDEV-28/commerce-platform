package com.gabrielsena.commerce.security;

import com.gabrielsena.commerce.cart.api.ShoppingCartController;
import com.gabrielsena.commerce.order.api.PublicOrderController;
import com.gabrielsena.commerce.shipping.api.ShippingEstimateController;
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

public class PublicApiRateLimitInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(PublicApiRateLimitInterceptor.class);
    private final PublicApiRateLimiter limiter;

    public PublicApiRateLimitInterceptor(PublicApiRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        var category = category(handler);
        if (category == null) return true;
        String address = request.getRemoteAddr();
        if (address == null || address.isBlank() || address.length() > 45) {
            log.error("Cannot rate limit public API: invalid remote address");
            error(response, 503, "PUBLIC_RATE_LIMIT_UNAVAILABLE");
            return false;
        }
        try {
            var decision = limiter.check(address, category);
            if (!decision.allowed()) {
                response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
                error(response, 429, "PUBLIC_RATE_LIMITED");
                return false;
            }
        } catch (DataAccessException exception) {
            log.error("Public API rate limiter unavailable", exception);
            error(response, 503, "PUBLIC_RATE_LIMIT_UNAVAILABLE");
            return false;
        }
        return true;
    }

    private static void error(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }

    private static PublicApiRateLimiter.Category category(Object handler) {
        if (!(handler instanceof HandlerMethod method)) return null;
        Class<?> type = method.getBeanType();
        String name = method.getMethod().getName();
        if (type == ShoppingCartController.class && name.equals("create")) {
            return PublicApiRateLimiter.Category.CART_CREATE;
        }
        if (type == ShippingEstimateController.class
                && (name.equals("product") || name.equals("cart") || name.equals("checkout"))) {
            return PublicApiRateLimiter.Category.SHIPPING_QUOTE;
        }
        if (type == PublicOrderController.class && name.equals("create")) {
            return PublicApiRateLimiter.Category.ORDER_CREATE;
        }
        return null;
    }
}
