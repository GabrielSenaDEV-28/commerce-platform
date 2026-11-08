package com.gabrielsena.commerce.customer.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class CustomerProfileService {
    private final JdbcTemplate jdbc;

    public CustomerProfileService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Profile(String name, String email, String phone) { }
    public record ProfileRequest(@NotBlank @Size(max = 150) String name,
                                 @NotBlank @Pattern(regexp = "[0-9]{10,11}") String phone) { }
    public record AddressRequest(@NotBlank @Size(max = 150) String recipient,
                                 @NotBlank @Pattern(regexp = "[0-9]{8}") String postalCode,
                                 @NotBlank @Size(max = 150) String street,
                                 @NotBlank @Size(max = 30) String number,
                                 @Size(max = 150) String complement,
                                 @NotBlank @Size(max = 100) String district,
                                 @NotBlank @Size(max = 100) String city,
                                 @NotBlank @Pattern(regexp = "AC|AL|AP|AM|BA|CE|DF|ES|GO|MA|MT|MS|MG|PA|PB|PR|PE|PI|RJ|RN|RS|RO|RR|SC|SP|SE|TO") String state) { }
    public record Address(UUID id, String recipient, String postalCode, String street, String number,
                          String complement, String district, String city, String state, String country) { }

    @Transactional(readOnly = true)
    public Profile profile(UUID store, UUID customer) {
        return jdbc.queryForObject("""
                SELECT name, email, phone FROM customer_accounts WHERE store_id = ? AND customer_id = ?
                """, (rs, row) -> new Profile(rs.getString(1), rs.getString(2), rs.getString(3)), store, customer);
    }

    @Transactional
    public Profile updateProfile(UUID store, UUID customer, ProfileRequest request) {
        jdbc.update("UPDATE customer_accounts SET name = ?, phone = ? WHERE store_id = ? AND customer_id = ?",
                request.name().strip(), request.phone(), store, customer);
        return profile(store, customer);
    }

    @Transactional(readOnly = true)
    public List<Address> addresses(UUID store, UUID customer) {
        return jdbc.query("""
                SELECT * FROM customer_addresses WHERE store_id = ? AND customer_id = ? ORDER BY address_id
                """, (rs, row) -> address(rs), store, customer);
    }

    @Transactional(readOnly = true)
    public Address get(UUID store, UUID customer, UUID id) {
        List<Address> result = jdbc.query("""
                SELECT * FROM customer_addresses WHERE store_id = ? AND customer_id = ? AND address_id = ?
                """, (rs, row) -> address(rs), store, customer, id);
        if (result.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found.");
        }
        return result.getFirst();
    }

    @Transactional
    public Address create(UUID store, UUID customer, AddressRequest request) {
        lockCustomer(store, customer);
        int count = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM customer_addresses WHERE store_id = ? AND customer_id = ?",
                Integer.class, store, customer));
        if (count >= 10) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Address limit reached.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO customer_addresses (address_id, store_id, customer_id, recipient, postal_code,
                    street, house_number, complement, district, city, state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, store, customer, request.recipient().strip(), request.postalCode(), request.street().strip(),
                request.number().strip(), clean(request.complement()), request.district().strip(),
                request.city().strip(), request.state());
        return get(store, customer, id);
    }

    @Transactional
    public Address update(UUID store, UUID customer, UUID id, AddressRequest request) {
        lockCustomer(store, customer);
        int changed = jdbc.update("""
                UPDATE customer_addresses SET recipient = ?, postal_code = ?, street = ?, house_number = ?,
                    complement = ?, district = ?, city = ?, state = ?
                WHERE store_id = ? AND customer_id = ? AND address_id = ?
                """, request.recipient().strip(), request.postalCode(), request.street().strip(),
                request.number().strip(), clean(request.complement()), request.district().strip(),
                request.city().strip(), request.state(), store, customer, id);
        if (changed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found.");
        }
        return get(store, customer, id);
    }

    @Transactional
    public void delete(UUID store, UUID customer, UUID id) {
        lockCustomer(store, customer);
        if (jdbc.update("DELETE FROM customer_addresses WHERE store_id = ? AND customer_id = ? AND address_id = ?",
                store, customer, id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found.");
        }
    }

    public Profile lockCustomer(UUID store, UUID customer) {
        List<Profile> result = jdbc.query("""
                SELECT name, email, phone FROM customer_accounts WHERE store_id = ? AND customer_id = ? FOR UPDATE
                """, (rs, row) -> new Profile(rs.getString(1), rs.getString(2), rs.getString(3)), store, customer);
        if (result.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return result.getFirst();
    }

    private static Address address(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Address(rs.getObject("address_id", UUID.class), rs.getString("recipient"),
                rs.getString("postal_code"), rs.getString("street"), rs.getString("house_number"),
                rs.getString("complement"), rs.getString("district"), rs.getString("city"),
                rs.getString("state"), "BR");
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
