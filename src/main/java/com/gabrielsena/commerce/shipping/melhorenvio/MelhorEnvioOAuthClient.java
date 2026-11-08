package com.gabrielsena.commerce.shipping.melhorenvio;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gabrielsena.commerce.shipping.melhorenvio.MelhorEnvioException.Reason;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class MelhorEnvioOAuthClient {
    private final RestClient client;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String userAgent;

    public MelhorEnvioOAuthClient(RestClient client, String clientId, String clientSecret,
                                 String redirectUri, String userAgent) {
        this.client = client;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.userAgent = userAgent;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Tokens {
        @JsonProperty("access_token")
        private String accessToken;
        @JsonProperty("refresh_token")
        private String refreshToken;
        @JsonProperty("expires_in")
        private Long expiresIn;
        @JsonProperty("token_type")
        private String tokenType;

        public String accessToken() { return accessToken; }
        public String refreshToken() { return refreshToken; }
        public long expiresIn() { return expiresIn; }
    }

    public Tokens exchange(String code) {
        requireValue(code);
        MultiValueMap<String, String> form = form("authorization_code");
        form.add("redirect_uri", redirectUri);
        form.add("code", code);
        return request(form);
    }

    public Tokens refresh(String refreshToken) {
        requireValue(refreshToken);
        MultiValueMap<String, String> form = form("refresh_token");
        form.add("refresh_token", refreshToken);
        return request(form);
    }

    void validateConfiguration() {
        form("authorization_code");
    }

    private MultiValueMap<String, String> form(String grant) {
        if (blankOrUnsafe(clientId) || blankOrUnsafe(clientSecret) || blankOrUnsafe(redirectUri)
                || blankOrUnsafe(userAgent)) {
            throw new MelhorEnvioException(Reason.CONFIGURATION);
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", grant);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        return form;
    }

    private Tokens request(MultiValueMap<String, String> form) {
        Tokens result;
        try {
            result = client.post().uri("/oauth/token")
                    .headers(headers -> {
                        headers.set("User-Agent", userAgent);
                        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
                        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                    }).body(form).retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                        int status = response.getStatusCode().value();
                        throw new MelhorEnvioException(status == 400 || status == 401 || status == 403
                                ? Reason.AUTHENTICATION : status == 429 ? Reason.RATE_LIMIT
                                : status >= 500 ? Reason.UNAVAILABLE : Reason.PROVIDER_REJECTED);
                    }).body(Tokens.class);
        } catch (ResourceAccessException exception) {
            throw new MelhorEnvioException(Reason.UNAVAILABLE);
        } catch (RestClientException exception) {
            throw new MelhorEnvioException(Reason.INVALID_RESPONSE);
        }
        if (result == null || blankOrUnsafe(result.accessToken) || blankOrUnsafe(result.refreshToken)
                || result.expiresIn == null || result.expiresIn <= 0
                || result.tokenType == null || !result.tokenType.equalsIgnoreCase("Bearer")) {
            throw new MelhorEnvioException(Reason.INVALID_RESPONSE);
        }
        return result;
    }

    private static void requireValue(String value) {
        if (blankOrUnsafe(value)) {
            throw new IllegalArgumentException("Invalid authorization credential.");
        }
    }

    private static boolean blankOrUnsafe(String value) {
        return value == null || value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }
}
