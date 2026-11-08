package com.gabrielsena.commerce.customer.api;

import com.gabrielsena.commerce.customer.application.CustomerAuthService;
import com.gabrielsena.commerce.customer.application.CustomerProfileService;
import com.gabrielsena.commerce.customer.application.CustomerProfileService.Address;
import com.gabrielsena.commerce.customer.application.CustomerProfileService.AddressRequest;
import com.gabrielsena.commerce.customer.application.CustomerProfileService.Profile;
import com.gabrielsena.commerce.customer.application.CustomerProfileService.ProfileRequest;
import com.gabrielsena.commerce.store.PublicStoreResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/public/customer")
public class CustomerProfileController {
    private final CustomerProfileService profiles;
    private final CustomerAuthService auth;
    private final PublicStoreResolver stores;

    public CustomerProfileController(CustomerProfileService profiles, CustomerAuthService auth, PublicStoreResolver stores) {
        this.profiles = profiles;
        this.auth = auth;
        this.stores = stores;
    }

    @GetMapping("/profile")
    public ResponseEntity<Profile> profile(HttpServletRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID store = stores.resolve(request.getServerName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.profile(store, auth.customer(store, jwt).id()));
    }

    @PutMapping("/profile")
    public ResponseEntity<Profile> updateProfile(HttpServletRequest request, @AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody ProfileRequest body) {
        UUID store = stores.resolve(request.getServerName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.updateProfile(store, auth.customer(store, jwt).id(), body));
    }

    @GetMapping("/addresses")
    public ResponseEntity<List<Address>> list(HttpServletRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID store = stores.resolve(request.getServerName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.addresses(store, auth.customer(store, jwt).id()));
    }

    @GetMapping("/addresses/{id}")
    public ResponseEntity<Address> get(HttpServletRequest request, @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID store = stores.resolve(request.getServerName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.get(store, auth.customer(store, jwt).id(), id));
    }

    @PostMapping("/addresses")
    public ResponseEntity<Address> create(HttpServletRequest request, @AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody AddressRequest body) {
        UUID store = stores.resolve(request.getServerName());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(profiles.create(store, auth.customer(store, jwt).id(), body));
    }

    @PutMapping("/addresses/{id}")
    public ResponseEntity<Address> update(HttpServletRequest request, @AuthenticationPrincipal Jwt jwt,
                                          @PathVariable UUID id, @Valid @RequestBody AddressRequest body) {
        UUID store = stores.resolve(request.getServerName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.update(store, auth.customer(store, jwt).id(), id, body));
    }

    @DeleteMapping("/addresses/{id}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID store = stores.resolve(request.getServerName());
        profiles.delete(store, auth.customer(store, jwt).id(), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
