package com.gabrielsena.commerce.catalog.api;

import com.gabrielsena.commerce.api.PageResponse;
import com.gabrielsena.commerce.catalog.application.PublicCatalogService;
import com.gabrielsena.commerce.catalog.application.PublicCatalogService.PublicProduct;
import com.gabrielsena.commerce.catalog.application.PublicCatalogService.PublicVariant;
import com.gabrielsena.commerce.store.PublicStoreResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.gabrielsena.commerce.api.ApiRequestSupport.pageable;

@RestController
@RequestMapping("/api/public/catalog/products")
public class PublicCatalogController {
    private final PublicCatalogService catalog;
    private final PublicStoreResolver stores;

    public PublicCatalogController(PublicCatalogService catalog, PublicStoreResolver stores) {
        this.catalog = catalog;
        this.stores = stores;
    }

    @GetMapping
    public PageResponse<PublicProduct> list(HttpServletRequest request, @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(catalog.products(stores.resolve(request.getServerName()), pageable(page, size, "name", "id")));
    }

    @GetMapping("/{productId}")
    public PublicProduct get(HttpServletRequest request, @PathVariable UUID productId) {
        return catalog.product(stores.resolve(request.getServerName()), productId);
    }

    @GetMapping("/{productId}/variants")
    public PageResponse<PublicVariant> variants(HttpServletRequest request, @PathVariable UUID productId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(catalog.variants(stores.resolve(request.getServerName()), productId, pageable(page, size, "id")));
    }
}
