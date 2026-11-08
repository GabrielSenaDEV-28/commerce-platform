package com.gabrielsena.commerce.store.api;

import com.gabrielsena.commerce.store.PublicStoreResolver;
import com.gabrielsena.commerce.store.application.StoreShippingSettingsService;
import com.gabrielsena.commerce.store.application.StoreShippingSettingsService.PickupOption;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicPickupController {
    private final PublicStoreResolver stores;
    private final StoreShippingSettingsService settings;

    public PublicPickupController(PublicStoreResolver stores, StoreShippingSettingsService settings) {
        this.stores = stores;
        this.settings = settings;
    }

    @GetMapping("/api/public/pickup")
    public ResponseEntity<PickupOption> get(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(settings.pickupOption(stores.resolve(request.getServerName())));
    }
}
