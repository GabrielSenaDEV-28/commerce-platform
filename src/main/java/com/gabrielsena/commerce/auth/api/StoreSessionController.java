package com.gabrielsena.commerce.auth.api;

import com.gabrielsena.commerce.auth.InvalidCredentialsException;
import com.gabrielsena.commerce.auth.InvalidRefreshTokenException;
import com.gabrielsena.commerce.auth.application.StoreBrowserSessions;
import com.gabrielsena.commerce.auth.application.TokenService;
import com.gabrielsena.commerce.customer.api.CustomerAuthController.Registration;
import com.gabrielsena.commerce.store.PublicStoreResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Browser login/session API: the refresh token is only ever exchanged through an HttpOnly cookie. */
@RestController
@RequestMapping(StoreSessionCookies.PATH)
public class StoreSessionController {
    private final StoreBrowserSessions sessions;
    private final PublicStoreResolver stores;
    private final StoreSessionCookies cookies;

    public StoreSessionController(StoreBrowserSessions sessions, PublicStoreResolver stores,
                                  StoreSessionCookies cookies) {
        this.sessions = sessions;
        this.stores = stores;
        this.cookies = cookies;
    }

    public record SessionResponse(String accessToken, String tokenType, long expiresIn, String accountKind,
                                  String identity) { }

    @PostMapping("/login")
    public ResponseEntity<SessionResponse> login(HttpServletRequest request, @Valid @RequestBody LoginRequest body) {
        var session = sessions.login(stores.resolve(request.getServerName()), body.email(), body.password());
        return respond(request, HttpStatus.OK, session);
    }

    @PostMapping("/register")
    public ResponseEntity<SessionResponse> register(HttpServletRequest request,
                                                    @Valid @RequestBody Registration body) {
        var session = sessions.register(stores.resolve(request.getServerName()), body.name(), body.email(),
                body.password());
        return respond(request, HttpStatus.CREATED, session);
    }

    @PostMapping("/refresh")
    public ResponseEntity<SessionResponse> refresh(HttpServletRequest request,
            @CookieValue(name = StoreSessionCookies.NAME, required = false) String token) {
        var store = stores.resolve(request.getServerName());
        if (!plausible(token)) {
            throw new InvalidRefreshTokenException();
        }
        return respond(request, HttpStatus.OK, sessions.refresh(store, token));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request,
            @CookieValue(name = StoreSessionCookies.NAME, required = false) String token) {
        var store = stores.resolve(request.getServerName());
        if (plausible(token)) {
            sessions.logout(store, token);
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookies.clear(request)).build();
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ProblemDetail> handleInvalidSession(HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Store session is missing or invalid.");
        problem.setProperty("code", "STORE_SESSION_INVALID");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookies.clear(request)).body(problem);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore())
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication request is invalid."));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleStatus(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason()));
    }

    private ResponseEntity<SessionResponse> respond(HttpServletRequest request, HttpStatus status,
                                                    StoreBrowserSessions.Session session) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookies.issue(request, session.refreshToken(), session.refreshMaxAge()))
                .body(new SessionResponse(session.accessToken(), "Bearer", TokenService.ACCESS_TOKEN_SECONDS,
                        session.accountKind(), session.identity().toString()));
    }

    private static boolean plausible(String token) {
        return token != null && !token.isBlank() && token.length() <= 128;
    }
}
