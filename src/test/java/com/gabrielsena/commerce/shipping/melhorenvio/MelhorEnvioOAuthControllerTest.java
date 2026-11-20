package com.gabrielsena.commerce.shipping.melhorenvio;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MelhorEnvioOAuthControllerTest {
    private final MelhorEnvioOAuthService service = mock(MelhorEnvioOAuthService.class);
    private final MelhorEnvioOAuthController controller = new MelhorEnvioOAuthController(service);

    @Test
    void callbackReturnsToFixedPanelAndClearsCookieWithoutLeakingCode() {
        var response = controller.callback("state", "secret-code", null, "browser-secret");
        verify(service).complete("state", "browser-secret", "secret-code", null);
        assertEquals(HttpStatus.SEE_OTHER, response.getStatusCode());
        assertEquals("/painel/entrega?melhorEnvio=connected", response.getHeaders().getLocation().toString());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("no-referrer", response.getHeaders().getFirst("Referrer-Policy"));
        String cookie = response.getHeaders().getFirst("Set-Cookie");
        assertTrue(cookie.contains("Max-Age=0"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("HttpOnly"));
        assertNull(response.getBody());
    }

    @Test
    void invalidStateAndProviderFailureReturnToPanelWithoutSuccess() {
        doThrow(new IllegalArgumentException("Invalid state")).when(service).complete("invalid", null, "code", null);
        assertEquals("/painel/entrega?melhorEnvio=failed",
                controller.callback("invalid", "code", null, null).getHeaders().getLocation().toString());
        doThrow(new MelhorEnvioException(MelhorEnvioException.Reason.AUTHENTICATION))
                .when(service).complete("state", "browser", null, "access_denied");
        assertEquals("/painel/entrega?melhorEnvio=failed",
                controller.callback("state", null, "access_denied", "browser").getHeaders().getLocation().toString());
    }

    @Test
    void configurationFailureHasActionableCodeAndNoCredentials() {
        var response = controller.integrationError(new MelhorEnvioException(MelhorEnvioException.Reason.CONFIGURATION));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("MELHOR_ENVIO_CONFIGURATION", response.getBody().get("code"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(HttpStatus.BAD_GATEWAY, controller.integrationError(
                new MelhorEnvioException(MelhorEnvioException.Reason.UNAVAILABLE)).getStatusCode());
    }

    @Test
    void insecureRequestsDoNotStartAuthorization() {
        assertEquals(HttpStatus.BAD_REQUEST, controller.start(new MockHttpServletRequest(), null).getStatusCode());
        verifyNoInteractions(service);
    }
}
