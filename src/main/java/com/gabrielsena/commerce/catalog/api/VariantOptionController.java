package com.gabrielsena.commerce.catalog.api;

import com.gabrielsena.commerce.catalog.CatalogStoreResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static com.gabrielsena.commerce.api.ApiRequestSupport.actor;

@RestController
@RequestMapping("/api/variant-options")
public class VariantOptionController {
    private final CatalogStoreResolver stores;
    private final JdbcTemplate jdbc;

    public VariantOptionController(CatalogStoreResolver stores, JdbcTemplate jdbc) {
        this.stores = stores;
        this.jdbc = jdbc;
    }

    public record Option(UUID id, String kind, String name, String colorHex) { }
    public record OptionRequest(@NotBlank @Pattern(regexp = "COLOR|SIZE") String kind,
                                @NotBlank @Size(max = 80) String name,
                                String colorHex) { }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleStatus(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason()));
    }

    @GetMapping
    public ResponseEntity<List<Option>> list(@AuthenticationPrincipal Jwt jwt) {
        UUID store = stores.storeFor(actor(jwt));
        List<Option> options = jdbc.query("""
                SELECT id, kind, name, color_hex FROM store_variant_options
                WHERE store_id = ? ORDER BY kind, name, id
                """, (rs, row) -> new Option(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getString(4)), store);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(options);
    }

    @PostMapping
    public ResponseEntity<Option> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OptionRequest request) {
        UUID store = stores.storeFor(actor(jwt));
        String name = request.name().strip();
        if (name.isBlank() || ("SIZE".equals(request.kind()) && (name.length() > 40 || request.colorHex() != null))
                || ("COLOR".equals(request.kind()) && (request.colorHex() == null || !request.colorHex().matches("#[0-9A-Fa-f]{6}")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid option.");
        }
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO store_variant_options (id, store_id, kind, name, color_hex)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, id, store, request.kind(), name, request.colorHex());
        if (inserted == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Option already exists.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new Option(id, request.kind(), name, request.colorHex()));
    }
}
