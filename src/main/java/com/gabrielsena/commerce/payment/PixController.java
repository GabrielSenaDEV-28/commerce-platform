package com.gabrielsena.commerce.payment;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.gabrielsena.commerce.customer.application.CustomerAuthService;
import com.gabrielsena.commerce.store.PublicStoreResolver;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.servlet.http.HttpServletRequest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PixController {
    private final PixService payments;
    private final PublicStoreResolver stores;
    private final CustomerAuthService customers;
    private final String webhookSecret;

    PixController(PixService payments, PublicStoreResolver stores, CustomerAuthService customers,
                  @Value("${payments.pix.webhook-secret:}") String webhookSecret) {
        this.payments = payments;
        this.stores = stores;
        this.customers = customers;
        this.webhookSecret = webhookSecret;
    }

    record PaymentResponse(boolean enabled, String status, String copyCode, String qrImage, Instant expiresAt) { }

    @GetMapping("/api/public/customer/orders/{orderId}/pix")
    ResponseEntity<PaymentResponse> get(HttpServletRequest request, @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        UUID store = stores.resolve(request.getServerName());
        return response(payments.get(store, customers.customer(store, jwt).id(), orderId));
    }

    @PostMapping("/api/public/customer/orders/{orderId}/pix")
    ResponseEntity<PaymentResponse> create(HttpServletRequest request, @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        UUID store = stores.resolve(request.getServerName());
        return response(payments.create(store, customers.customer(store, jwt).id(), orderId));
    }

    @PostMapping("/api/public/payments/mercado-pago/webhook")
    ResponseEntity<Void> webhook(@RequestParam("data.id") String remote, @RequestParam("type") String type,
                                 @RequestHeader("x-request-id") String requestId,
                                 @RequestHeader("x-signature") String signature) {
        if (webhookSecret.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!"order".equals(type) || !MercadoPagoSandbox.validId(remote)
                || !validSignature(remote, requestId, signature, webhookSecret)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Mercado Pago webhook signature.");
        }
        payments.notify(remote);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }

    static boolean validSignature(String id, String requestId, String signature, String secret) {
        if (!requestId.matches("[A-Za-z0-9_-]{1,128}") || signature.length() > 256) return false;
        String ts = null;
        String digest = null;
        for (String entry : signature.split(",")) {
            String[] parts = entry.strip().split("=", 2);
            if (parts.length != 2) return false;
            if (parts[0].equals("ts")) {
                if (ts != null) return false;
                ts = parts[1];
            } else if (parts[0].equals("v1")) {
                if (digest != null) return false;
                digest = parts[1];
            }
        }
        if (ts == null || !ts.matches("\\d{10}|\\d{13}") || digest == null || !digest.matches("[0-9a-fA-F]{64}")) return false;
        long seconds = ts.length() == 13 ? Long.parseLong(ts) / 1000 : Long.parseLong(ts);
        if (Math.abs(Instant.now().getEpochSecond() - seconds) > 300) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
            String manifest = "id:" + id.toLowerCase(Locale.ROOT) + ";request-id:" + requestId + ";ts:" + ts + ";";
            return MessageDigest.isEqual(mac.doFinal(manifest.getBytes(UTF_8)), HexFormat.of().parseHex(digest));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Could not verify Mercado Pago webhook.", failure);
        }
    }

    private ResponseEntity<PaymentResponse> response(PixService.View view) {
        String code = view.copyCode();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new PaymentResponse(view.enabled(), view.status(), code, code == null ? null : qr(code), view.expiresAt()));
    }

    private String qr(String code) {
        try (var output = new ByteArrayOutputStream()) {
            var matrix = new QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 256, 256);
            var image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) image.setRGB(x, y, matrix.get(x, y) ? 0xff000000 : 0xffffffff);
            }
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG encoder unavailable.");
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException | WriterException failure) {
            throw new IllegalStateException("Could not render Pix QR.", failure);
        }
    }
}
