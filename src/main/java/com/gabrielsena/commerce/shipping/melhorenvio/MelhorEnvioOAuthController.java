package com.gabrielsena.commerce.shipping.melhorenvio;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

import static com.gabrielsena.commerce.api.ApiRequestSupport.actor;

@RestController
public class MelhorEnvioOAuthController {
    private static final String COOKIE = "__Host-me-oauth";
    private final MelhorEnvioOAuthService service;

    public MelhorEnvioOAuthController(MelhorEnvioOAuthService service) {
        this.service = service;
    }

    @PostMapping("/api/integrations/melhor-envio/connect")
    public ResponseEntity<Map<String, String>> start(HttpServletRequest request, @AuthenticationPrincipal Jwt jwt) {
        if (!request.isSecure()) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                    .body(Map.of("error", "HTTPS is required."));
        }
        var start = service.start(actor(jwt), request.getServerName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookie(start.browserSecret(), Duration.ofMinutes(10)))
                .body(Map.of("authorizationUrl", start.authorizationUrl()));
    }

    @GetMapping("/api/integrations/melhor-envio")
    public ResponseEntity<Map<String, String>> status(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("status", service.status(actor(jwt))));
    }

    @DeleteMapping("/api/integrations/melhor-envio")
    public ResponseEntity<Void> disconnect(@AuthenticationPrincipal Jwt jwt) {
        service.disconnect(actor(jwt));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/api/public/integrations/melhor-envio/callback")
    public ResponseEntity<String> callback(@RequestParam(required = false) String state,
            @RequestParam(required = false) String code, @RequestParam(required = false) String error,
            @CookieValue(name = COOKIE, required = false) String browser) {
        try {
            service.complete(state, browser, code, error);
        } catch (IllegalArgumentException exception) {
            return callbackResponse(HttpStatus.BAD_REQUEST, "Invalid authorization response.");
        } catch (MelhorEnvioException exception) {
            return callbackResponse(HttpStatus.BAD_GATEWAY, "Authorization could not be completed.");
        }
        return callbackResponse(HttpStatus.OK, "Authorization completed.");
    }

    private static ResponseEntity<String> callbackResponse(HttpStatus status, String body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO)).body(body);
    }

    private static String cookie(String value, Duration age) {
        return ResponseCookie.from(COOKIE, value).httpOnly(true).secure(true).sameSite("Lax")
                .path("/").maxAge(age).build().toString();
    }
}
