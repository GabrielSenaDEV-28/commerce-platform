package com.gabrielsena.commerce.store.api;

import com.gabrielsena.commerce.store.application.StoreShippingSettingsService;
import com.gabrielsena.commerce.store.application.StoreShippingSettingsService.Settings;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.gabrielsena.commerce.api.ApiRequestSupport.actor;

@RestController
@RequestMapping("/api/store/shipping-settings")
public class StoreShippingSettingsController {
    private final StoreShippingSettingsService settings;

    public StoreShippingSettingsController(StoreShippingSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public ResponseEntity<Settings> get(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settings.get(actor(jwt)));
    }

    @PutMapping
    public ResponseEntity<Settings> update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Settings request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settings.update(actor(jwt), request));
    }
}
