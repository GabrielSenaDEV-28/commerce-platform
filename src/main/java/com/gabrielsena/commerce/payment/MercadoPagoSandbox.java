package com.gabrielsena.commerce.payment;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Component
public class MercadoPagoSandbox {
    private final RestClient client;
    private final boolean enabled;

    MercadoPagoSandbox(@Value("${payments.pix.sandbox-enabled:false}") boolean enabled,
                       @Value("${payments.pix.test-access-token:}") String testToken) {
        this.enabled = enabled;
        if (enabled && testToken.isBlank()) {
            throw new IllegalArgumentException("Pix sandbox requires a test Access Token.");
        }
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4)).build());
        factory.setReadTimeout(Duration.ofSeconds(8));
        client = RestClient.builder().baseUrl("https://api.mercadopago.com")
                .requestFactory(factory).defaultHeaders(headers -> headers.setBearerAuth(testToken)).build();
    }

    public boolean enabled() { return enabled; }

    public record Remote(String id, String reference, BigDecimal total, String qr, Instant issuedAt,
                  boolean sandboxTicket, boolean paid, boolean expired, boolean requiresReview) { }

    public Remote create(UUID ref, BigDecimal total) {
        String amount = total.toPlainString();
        try {
            return parse(client.post().uri("/v1/orders").header("X-Idempotency-Key", ref.toString())
                    .body(Map.of("type", "online", "processing_mode", "automatic", "total_amount", amount,
                            "external_reference", ref.toString(),
                            "payer", Map.of("email", "test_payer@example.com", "first_name", "APRO"),
                            "transactions", Map.of("payments", List.of(Map.of("amount", amount, "expiration_time", "PT30M",
                                    "payment_method", Map.of("id", "pix", "type", "bank_transfer"))))))
                    .retrieve().body(JsonNode.class));
        } catch (RestClientException failure) {
            throw unavailable();
        }
    }

    public Remote fetch(String id) {
        try {
            return parse(client.get().uri("/v1/orders/{id}", id).retrieve().body(JsonNode.class));
        } catch (RestClientException failure) {
            throw unavailable();
        }
    }

    private Remote parse(JsonNode root) {
        if (root == null) throw unavailable();
        try {
            JsonNode payments = root.path("transactions").path("payments");
            JsonNode payment = payments.path(0);
            JsonNode method = payment.path("payment_method");
            String id = root.path("id").asText("");
            BigDecimal total = new BigDecimal(root.path("total_amount").asText(""));
            if (!validId(id) || payments.size() != 1 || !method.path("id").asText("").equals("pix")
                    || !method.path("type").asText("").equals("bank_transfer")
                    || !root.path("country_code").asText("").equals("BRA")
                    || total.compareTo(new BigDecimal(payment.path("amount").asText(""))) != 0) throw unavailable();
            String status = root.path("status").asText("");
            boolean accredited = status.equals("processed") && root.path("status_detail").asText("").equals("accredited")
                    && payment.path("status").asText("").equals("processed")
                    && payment.path("status_detail").asText("").equals("accredited");
            boolean paid = accredited && total.compareTo(new BigDecimal(payment.path("paid_amount").asText("0"))) == 0;
            String ticket = method.path("ticket_url").asText("");
            URI url = URI.create(ticket);
            String path = url.getPath();
            boolean sandbox = "https".equals(url.getScheme()) && "www.mercadopago.com.br".equals(url.getHost())
                    && path != null && path.startsWith("/sandbox/payments/");
            String qr = status.equals("action_required") && root.path("status_detail").asText("").equals("waiting_transfer")
                    ? method.path("qr_code").asText("") : "";
            if (qr.length() > 1024) throw unavailable();
            return new Remote(id, root.path("external_reference").asText(""), total, qr,
                    Instant.parse(root.path("created_date").asText("")), sandbox, paid,
                    List.of("expired", "canceled", "cancelled", "failed").contains(status),
                    List.of("processed", "refunded", "charged_back").contains(status) && !paid);
        } catch (IllegalArgumentException | java.time.DateTimeException failure) {
            throw unavailable();
        }
    }

    static boolean validId(String id) { return id != null && id.matches("ORD[A-Za-z0-9]{1,77}"); }

    static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Pix sandbox is temporarily unavailable.");
    }
}
