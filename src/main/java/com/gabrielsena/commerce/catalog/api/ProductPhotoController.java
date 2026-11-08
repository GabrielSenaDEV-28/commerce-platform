package com.gabrielsena.commerce.catalog.api;

import com.gabrielsena.commerce.catalog.application.ProductPhotoService;
import com.gabrielsena.commerce.catalog.application.ProductPhotoService.Photo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static com.gabrielsena.commerce.api.ApiRequestSupport.actor;

@RestController
@RequestMapping("/api/products/{productId}/photos")
public class ProductPhotoController {
    private final ProductPhotoService photos;

    public ProductPhotoController(ProductPhotoService photos) {
        this.photos = photos;
    }

    public record OrderRequest(@NotNull List<@NotNull UUID> photoIds) { }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Photo> upload(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId,
                                        @RequestPart("file") MultipartFile file) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(photos.upload(actor(jwt), productId, file));
    }

    @GetMapping
    public List<Photo> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId) {
        return photos.list(actor(jwt), productId);
    }

    @PutMapping("/order")
    public List<Photo> reorder(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId,
                               @Valid @RequestBody OrderRequest request) {
        return photos.reorder(actor(jwt), productId, request.photoIds());
    }

    @DeleteMapping("/{photoId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId,
                                       @PathVariable UUID photoId) {
        photos.delete(actor(jwt), productId, photoId);
        return ResponseEntity.noContent().build();
    }
}
