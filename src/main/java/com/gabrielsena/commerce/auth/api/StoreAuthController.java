package com.gabrielsena.commerce.auth.api;

import com.gabrielsena.commerce.auth.InvalidCredentialsException;
import com.gabrielsena.commerce.auth.application.AuthenticateStoreAccount;
import com.gabrielsena.commerce.auth.application.TokenService;
import com.gabrielsena.commerce.store.PublicStoreResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/public/store-auth")
public class StoreAuthController {
    private final AuthenticateStoreAccount auth;
    private final PublicStoreResolver stores;

    public StoreAuthController(AuthenticateStoreAccount auth, PublicStoreResolver stores) {
        this.auth = auth;
        this.stores = stores;
    }

    public record LoginResponse(String accessToken, String refreshToken, String tokenType,
                                long expiresIn, String accountKind) { }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(HttpServletRequest request, @Valid @RequestBody LoginRequest body) {
        var result = auth.execute(stores.resolve(request.getServerName()), body.email(), body.password());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new LoginResponse(
                result.tokens().accessToken(), result.tokens().refreshToken(), "Bearer",
                TokenService.ACCESS_TOKEN_SECONDS, result.accountKind()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleStatus(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCredentials() {
        return ResponseEntity.status(401).cacheControl(CacheControl.noStore())
                .body(ProblemDetail.forStatusAndDetail(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "Authentication request is invalid."));
    }
}
