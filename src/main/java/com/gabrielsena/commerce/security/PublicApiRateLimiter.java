package com.gabrielsena.commerce.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PublicApiRateLimiter {
    public enum Category { CART_CREATE, SHIPPING_QUOTE, ORDER_CREATE }
    private final RateLimitCounter counter;
    private final int carts;
    private final int quotes;
    private final int orders;
    private final int windowSeconds;

    public PublicApiRateLimiter(RateLimitCounter counter,
            @Value("${public-api.rate-limit.cart-create:20}") int carts,
            @Value("${public-api.rate-limit.shipping-quote:60}") int quotes,
            @Value("${public-api.rate-limit.order-create:30}") int orders,
            @Value("${public-api.rate-limit.window-seconds:900}") int windowSeconds) {
        RateLimitCounter.validate(windowSeconds, carts, quotes, orders);
        this.counter = counter;
        this.carts = carts;
        this.quotes = quotes;
        this.orders = orders;
        this.windowSeconds = windowSeconds;
    }

    public RateLimitCounter.Decision check(String address, Category category) {
        int limit = switch (category) {
            case CART_CREATE -> carts;
            case SHIPPING_QUOTE -> quotes;
            case ORDER_CREATE -> orders;
        };
        return counter.check(RateLimitCounter.Table.PUBLIC_API, address, category.name(), limit, windowSeconds);
    }

    @Scheduled(fixedDelay = 300_000)
    public void cleanup() {
        counter.cleanup(RateLimitCounter.Table.PUBLIC_API, windowSeconds);
    }
}
