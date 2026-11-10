package com.gabrielsena.commerce.customer.api;

import com.gabrielsena.commerce.auth.api.AuthTokenResponse;
import com.gabrielsena.commerce.auth.api.LoginRequest;
import com.gabrielsena.commerce.auth.api.RefreshTokenRequest;
import com.gabrielsena.commerce.customer.application.CustomerAuthService;
import com.gabrielsena.commerce.customer.application.CustomerAuthService.Customer;
import com.gabrielsena.commerce.store.PublicStoreResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/public/customer-auth")
public class CustomerAuthController {
    private final CustomerAuthService auth;
    private final PublicStoreResolver stores;

    public CustomerAuthController(CustomerAuthService auth, PublicStoreResolver stores) {
        this.auth = auth;
        this.stores = stores;
    }

    public record Registration(@NotBlank @Size(max = 150) String name,
                               @NotBlank @Email @Size(max = 320) String email,
                               @NotBlank @Size(min = 12, max = 128) String password) { }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleStatus(ResponseStatusException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason());
        return ResponseEntity.status(exception.getStatusCode()).cacheControl(CacheControl.noStore()).body(problem);
    }

    @PostMapping("/register")
    public ResponseEntity<Customer> register(HttpServletRequest request, @Valid @RequestBody Registration body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(
                auth.register(stores.resolve(request.getServerName()), body.name(), body.email(), body.password()));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthTokenResponse> login(HttpServletRequest request, @Valid @RequestBody LoginRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AuthTokenResponse.from(
                auth.login(stores.resolve(request.getServerName()), body.email(), body.password())));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthTokenResponse> refresh(HttpServletRequest request, @Valid @RequestBody RefreshTokenRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AuthTokenResponse.from(
                auth.refresh(stores.resolve(request.getServerName()), body.refreshToken())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, @Valid @RequestBody RefreshTokenRequest body) {
        auth.logout(stores.resolve(request.getServerName()), body.refreshToken());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
