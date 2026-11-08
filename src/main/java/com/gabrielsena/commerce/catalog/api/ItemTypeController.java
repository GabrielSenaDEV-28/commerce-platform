package com.gabrielsena.commerce.catalog.api;

import com.gabrielsena.commerce.catalog.application.ItemTypeService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/item-types")
public class ItemTypeController {
    private final ItemTypeService service;

    public ItemTypeController(ItemTypeService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ItemTypeResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                    @Valid @RequestBody ItemTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ItemTypeResponse.from(service.create(actor(jwt), request.values())));
    }

    @GetMapping
    public PageResponse<ItemTypeResponse> list(@AuthenticationPrincipal Jwt jwt,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(service.list(actor(jwt), pageable(page, size, "name", "id"))
                .map(ItemTypeResponse::from));
    }

    @GetMapping("/{id}")
    public ItemTypeResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ItemTypeResponse.from(service.get(actor(jwt), id));
    }

    @GetMapping("/{id}/history")
    public PageResponse<ItemTypeHistoryResponse> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(service.history(actor(jwt), id, pageable(page, size, "occurredAt", "id"))
                .map(ItemTypeHistoryResponse::from));
    }

    @PutMapping("/{id}")
    public ItemTypeResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                   @Valid @RequestBody ItemTypeRequest request) {
        return ItemTypeResponse.from(service.update(actor(jwt), id, request.values()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.delete(actor(jwt), id);
        return ResponseEntity.noContent().build();
    }

    private UUID actor(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    private Pageable pageable(int page, int size, String... sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page or size");
        }
        return PageRequest.of(page, size, Sort.by(sort));
    }
}
