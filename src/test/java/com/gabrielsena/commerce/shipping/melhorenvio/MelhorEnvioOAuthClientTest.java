package com.gabrielsena.commerce.shipping.melhorenvio;

import com.gabrielsena.commerce.shipping.melhorenvio.MelhorEnvioException.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MelhorEnvioOAuthClientTest {
    private MockRestServiceServer server;
    private MelhorEnvioOAuthClient client;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder().baseUrl("https://sandbox.melhorenvio.com.br");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MelhorEnvioOAuthClient(builder.build(), "client", "secret",
                "https://panel.example.test/oauth/callback", "Commerce (support@example.test)");
    }

    @Test
    void exchangesCodeAndRefreshesWithDocumentedFormParameters() {
        MultiValueMap<String, String> code = new LinkedMultiValueMap<>();
        code.add("grant_type", "authorization_code");
        code.add("client_id", "client");
        code.add("client_secret", "secret");
        code.add("redirect_uri", "https://panel.example.test/oauth/callback");
        code.add("code", "code");
        MultiValueMap<String, String> refresh = new LinkedMultiValueMap<>();
        refresh.add("grant_type", "refresh_token");
        refresh.add("client_id", "client");
        refresh.add("client_secret", "secret");
        refresh.add("refresh_token", "refresh");
        for (var body : java.util.List.of(code, refresh)) {
            server.expect(requestTo("https://sandbox.melhorenvio.com.br/oauth/token"))
                    .andExpect(method(HttpMethod.POST)).andExpect(header("User-Agent", "Commerce (support@example.test)"))
                    .andExpect(content().formData(body))
                    .andRespond(withSuccess("""
                            {"token_type":"Bearer","expires_in":2592000,
                             "access_token":"access","refresh_token":"refresh"}
                            """, MediaType.APPLICATION_JSON));
        }
        var tokens = client.exchange("code");
        assertEquals("access", tokens.accessToken());
        assertEquals(2592000, tokens.expiresIn());
        assertFalse(tokens.toString().contains("access"));
        assertEquals("refresh", client.refresh("refresh").refreshToken());
        server.verify();
    }

    @Test
    void rejectsErrorsAndInvalidTokensWithoutLeakingProviderBody() {
        server.expect(requestTo("https://sandbox.melhorenvio.com.br/oauth/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("secret credential"));
        var failure = assertThrows(MelhorEnvioException.class, () -> client.exchange("code"));
        assertEquals(Reason.AUTHENTICATION, failure.reason());
        assertFalse(failure.toString().contains("secret"));
        assertNull(failure.getCause());
        server.verify();
    }

    @Test
    void rejectsIncompleteTokenResponse() {
        server.expect(requestTo("https://sandbox.melhorenvio.com.br/oauth/token"))
                .andRespond(withSuccess("{\"access_token\":\"access\"}", MediaType.APPLICATION_JSON));
        assertEquals(Reason.INVALID_RESPONSE,
                assertThrows(MelhorEnvioException.class, () -> client.exchange("code")).reason());
        server.verify();
    }
}
