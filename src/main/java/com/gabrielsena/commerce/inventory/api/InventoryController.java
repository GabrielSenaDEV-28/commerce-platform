package com.gabrielsena.commerce.inventory.api;

import com.gabrielsena.commerce.api.PageResponse;
import com.gabrielsena.commerce.inventory.application.InventoryService;
import com.gabrielsena.commerce.inventory.application.InventoryService.Audit;
import com.gabrielsena.commerce.inventory.application.InventoryService.Balance;
import com.gabrielsena.commerce.inventory.application.InventoryService.Movement;
import com.gabrielsena.commerce.inventory.application.InventoryService.MovementType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

import static com.gabrielsena.commerce.api.ApiRequestSupport.actor;
import static com.gabrielsena.commerce.api.ApiRequestSupport.pageable;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    public record MovementRequest(@NotNull MovementType type,
                                  @NotNull @Min(1) @Digits(integer = 10, fraction = 0)
                                  @DecimalMax("2147483647") BigDecimal quantity,
                                  @NotBlank @Size(max = 500) String reason) { }
    public record CountRequest(@NotNull UUID productId, @NotNull UUID variantId,
                               @NotNull @Min(0) @Digits(integer = 19, fraction = 0)
                               @DecimalMax("9223372036854775807") BigDecimal expectedVersion,
                               @NotNull @Min(0) @Digits(integer = 10, fraction = 0)
                               @DecimalMax("2147483647") BigDecimal quantity,
                               @NotBlank @Size(max = 500) String reason) { }

    @GetMapping("/products/{productId}/variants/{variantId}")
    public Balance balance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId, @PathVariable UUID variantId) {
        return inventory.balance(actor(jwt), productId, variantId);
    }

    @PostMapping("/products/{productId}/variants/{variantId}/movements")
    public ResponseEntity<Movement> move(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId,
                                         @PathVariable UUID variantId, @RequestHeader("Idempotency-Key") UUID key,
                                         @Valid @RequestBody MovementRequest request) {
        Movement movement = inventory.move(actor(jwt), productId, variantId, request.type(),
                request.quantity().intValueExact(), request.reason(), key);
        return ResponseEntity.status(HttpStatus.CREATED).body(movement);
    }

    @GetMapping("/products/{productId}/variants/{variantId}/movements")
    public PageResponse<Movement> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId,
                                          @PathVariable UUID variantId, @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(inventory.variantHistory(actor(jwt), productId, variantId,
                pageable(page, size, "occurredAt", "id")));
    }

    @PostMapping("/audits")
    public ResponseEntity<Audit> open(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventory.openAudit(actor(jwt)));
    }

    @GetMapping("/audits")
    public PageResponse<Audit> audits(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(inventory.audits(actor(jwt), pageable(page, size, "openedAt", "id")));
    }

    @GetMapping("/audits/{auditId}")
    public Audit audit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID auditId) {
        return inventory.audit(actor(jwt), auditId);
    }

    @PostMapping("/audits/{auditId}/close")
    public Audit close(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID auditId) {
        return inventory.closeAudit(actor(jwt), auditId);
    }

    @PostMapping("/audits/{auditId}/counts")
    public ResponseEntity<Movement> count(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID auditId,
                                          @Valid @RequestBody CountRequest request) {
        Movement movement = inventory.count(actor(jwt), auditId, request.productId(), request.variantId(),
                request.expectedVersion().longValueExact(), request.quantity().intValueExact(), request.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(movement);
    }

    @GetMapping("/audits/{auditId}/counts")
    public PageResponse<Movement> counts(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID auditId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(inventory.auditHistory(actor(jwt), auditId, pageable(page, size, "occurredAt", "id")));
    }
}
