package com.gabrielsena.commerce.store.application;

import com.gabrielsena.commerce.auth.MerchantAccountRepository;
import com.gabrielsena.commerce.store.StoreRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class StoreShippingSettingsService {
    private final MerchantAccountRepository accounts;
    private final StoreRepository stores;
    private final JdbcTemplate jdbc;

    public StoreShippingSettingsService(MerchantAccountRepository accounts, StoreRepository stores, JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.stores = stores;
        this.jdbc = jdbc;
    }

    public record Settings(@NotBlank @Pattern(regexp = "[0-9]{8}") String postalCode,
                           @NotBlank @Size(max = 150) String street,
                           @NotBlank @Size(max = 30) String number,
                           @Size(max = 150) String complement,
                           @NotBlank @Size(max = 100) String district,
                           @NotBlank @Size(max = 100) String city,
                           @NotBlank @Pattern(regexp = "AC|AL|AP|AM|BA|CE|DF|ES|GO|MA|MT|MS|MG|PA|PB|PR|PE|PI|RJ|RN|RS|RO|RR|SC|SP|SE|TO") String state,
                           @NotNull Boolean pickupEnabled,
                           @Size(max = 1000) String pickupInstructions) { }

    @Transactional(readOnly = true)
    public Settings get(UUID actor) {
        UUID store = storeFor(actor);
        return jdbc.query("""
                SELECT postal_code, street, house_number, complement, district, city, state,
                       pickup_enabled, pickup_instructions
                FROM store_shipping_settings WHERE store_id = ?
                """, (rs, row) -> new Settings(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getBoolean(8), rs.getString(9)), store).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipping settings not configured."));
    }

    @Transactional
    public Settings update(UUID actor, Settings request) {
        UUID store = storeFor(actor);
        jdbc.update("""
                INSERT INTO store_shipping_settings (store_id, postal_code, street, house_number, complement,
                    district, city, state, pickup_enabled, pickup_instructions)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (store_id) DO UPDATE SET postal_code = EXCLUDED.postal_code,
                    street = EXCLUDED.street, house_number = EXCLUDED.house_number,
                    complement = EXCLUDED.complement, district = EXCLUDED.district, city = EXCLUDED.city,
                    state = EXCLUDED.state, pickup_enabled = EXCLUDED.pickup_enabled,
                    pickup_instructions = EXCLUDED.pickup_instructions
                """, store, request.postalCode(), request.street().strip(), request.number().strip(),
                clean(request.complement()), request.district().strip(), request.city().strip(),
                request.state(), request.pickupEnabled(), clean(request.pickupInstructions()));
        return get(actor);
    }

    private UUID storeFor(UUID actor) {
        var account = accounts.findById(actor)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return stores.findByMerchantId(account.getMerchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN)).getId();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
