package com.gabrielsena.commerce.security;

import com.gabrielsena.commerce.cart.api.ShoppingCartController;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicApiRateLimitInterceptorTest {
    @Test
    void shouldFailClosedWhenDatabaseIsUnavailable() throws Exception {
        var limiter = mock(PublicApiRateLimiter.class);
        when(limiter.check(any(), any())).thenThrow(new DataAccessResourceFailureException("test failure"));
        var interceptor = new PublicApiRateLimitInterceptor(limiter);
        var handler = new HandlerMethod(mock(ShoppingCartController.class),
                ShoppingCartController.class.getMethod("create", HttpServletRequest.class));
        var response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(new MockHttpServletRequest("POST", "/api/public/cart"), response, handler));
        assertEquals(503, response.getStatus());
        assertEquals("{\"code\":\"PUBLIC_RATE_LIMIT_UNAVAILABLE\"}", response.getContentAsString());
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }
}
