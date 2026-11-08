package com.gabrielsena.commerce.auth.config;

import com.gabrielsena.commerce.auth.api.AuthController;
import com.gabrielsena.commerce.auth.api.LoginRequest;
import com.gabrielsena.commerce.auth.application.AuthenticationRateLimiter;
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

class AuthenticationRateLimitInterceptorTest {
    @Test
    void shouldFailClosedIfDatabaseIsUnavailable() throws Exception {
        var limiter = mock(AuthenticationRateLimiter.class);
        when(limiter.check(any(), any())).thenThrow(new DataAccessResourceFailureException("test failure"));
        var interceptor = new AuthenticationRateLimitInterceptor(limiter);
        var handler = new HandlerMethod(mock(AuthController.class),
                AuthController.class.getMethod("login", LoginRequest.class));
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        var response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, handler));
        assertEquals(503, response.getStatus());
        assertEquals("{\"code\":\"AUTH_RATE_LIMIT_UNAVAILABLE\"}", response.getContentAsString());
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }
}
