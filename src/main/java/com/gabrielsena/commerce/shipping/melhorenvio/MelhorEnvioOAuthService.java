package com.gabrielsena.commerce.shipping.melhorenvio;

import com.gabrielsena.commerce.catalog.CatalogStoreResolver;
import com.gabrielsena.commerce.security.OpaqueTokens;
import com.gabrielsena.commerce.shipping.melhorenvio.MelhorEnvioException.Reason;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Service
public class MelhorEnvioOAuthService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final CatalogStoreResolver stores;
    private final MelhorEnvioOAuthClient client;
    private final String clientId;
    private final String redirectUri;
    private final String encryptionKey;

    public MelhorEnvioOAuthService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, CatalogStoreResolver stores,
            MelhorEnvioOAuthClient client, @Value("${shipping.melhor-envio.client-id:}") String clientId,
            @Value("${shipping.melhor-envio.redirect-uri:}") String redirectUri,
            @Value("${shipping.melhor-envio.encryption-key:}") String encryptionKey) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.stores = stores;
        this.client = client;
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.encryptionKey = encryptionKey;
    }

    public record Start(String authorizationUrl, String browserSecret) { }
    private record Pending(UUID store, UUID generation) { }
    private record Connection(UUID generation, String status, byte[] access, byte[] refresh, boolean fresh) { }
    private record Claim(Connection connection, boolean refresh) { }

    public Start start(UUID actor, String host) {
        URI callback;
        try {
            callback = URI.create(redirectUri);
        } catch (IllegalArgumentException exception) {
            throw new MelhorEnvioException(Reason.CONFIGURATION);
        }
        if (clientId.isBlank() || !"https".equalsIgnoreCase(callback.getScheme()) || callback.getHost() == null
                || !callback.getHost().equalsIgnoreCase(host) || callback.getUserInfo() != null
                || callback.getFragment() != null || callback.getQuery() != null
                || !"/api/public/integrations/melhor-envio/callback".equals(callback.getPath())) {
            throw new MelhorEnvioException(Reason.CONFIGURATION);
        }
        new TokenCipher(encryptionKey);
        client.validateConfiguration();
        UUID store = stores.storeFor(actor);
        String state = OpaqueTokens.generate();
        String browser = OpaqueTokens.generate();
        UUID generation = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO melhor_envio_connections (store_id, generation, status, state_hash, browser_hash, state_expires_at)
                VALUES (?, ?, 'PENDING', ?, ?, clock_timestamp() + INTERVAL '10 minutes')
                ON CONFLICT (store_id) DO UPDATE SET generation = EXCLUDED.generation, status = 'PENDING',
                    state_hash = EXCLUDED.state_hash, browser_hash = EXCLUDED.browser_hash,
                    state_expires_at = EXCLUDED.state_expires_at, access_token = NULL, refresh_token = NULL,
                    access_expires_at = NULL
                """, store, generation, OpaqueTokens.hash(state), OpaqueTokens.hash(browser));
        String url = UriComponentsBuilder.fromUriString("https://sandbox.melhorenvio.com.br/oauth/authorize")
                .queryParam("client_id", clientId).queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code").queryParam("scope", "shipping-calculate")
                .queryParam("state", state).build().encode().toUriString();
        return new Start(url, browser);
    }

    public void complete(String state, String browser, String code, String error) {
        if (invalid(state) || invalid(browser)) {
            throw new IllegalArgumentException("Invalid authorization state.");
        }
        List<Pending> consumed = transactions.execute(status -> jdbc.query("""
                UPDATE melhor_envio_connections SET state_hash = NULL, browser_hash = NULL
                WHERE state_hash = ? AND browser_hash = ? AND status = 'PENDING'
                  AND state_expires_at > clock_timestamp()
                RETURNING store_id, generation
                """, (rs, row) -> new Pending(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
                OpaqueTokens.hash(state), OpaqueTokens.hash(browser)));
        if (consumed == null || consumed.size() != 1) {
            throw new IllegalArgumentException("Invalid or expired authorization state.");
        }
        Pending pending = consumed.getFirst();
        try {
            if (error != null || invalid(code)) {
                throw new MelhorEnvioException(Reason.AUTHENTICATION);
            }
            TokenCipher cipher = new TokenCipher(encryptionKey);
            var tokens = client.exchange(code);
            int updated = jdbc.update("""
                    UPDATE melhor_envio_connections SET status = 'CONNECTED', state_expires_at = NULL,
                        access_token = ?, refresh_token = ?,
                        access_expires_at = clock_timestamp() + (? * INTERVAL '1 second')
                    WHERE store_id = ? AND generation = ? AND status = 'PENDING' AND state_hash IS NULL
                    """, cipher.encrypt(pending.store(), tokens.accessToken()),
                    cipher.encrypt(pending.store(), tokens.refreshToken()), tokens.expiresIn(), pending.store(), pending.generation());
            if (updated != 1) {
                throw new MelhorEnvioException(Reason.AUTHENTICATION);
            }
        } catch (MelhorEnvioException exception) {
            markReauthorize(pending.store(), pending.generation(), "PENDING");
            throw exception;
        }
    }

    public String status(UUID actor) {
        UUID store = stores.storeFor(actor);
        return jdbc.query("""
                SELECT CASE WHEN status = 'PENDING' AND state_expires_at <= clock_timestamp()
                    THEN 'REAUTHORIZE' ELSE status END FROM melhor_envio_connections WHERE store_id = ?
                """,
                (rs, row) -> rs.getString(1), store).stream().findFirst().orElse("DISCONNECTED");
    }

    public void disconnect(UUID actor) {
        jdbc.update("DELETE FROM melhor_envio_connections WHERE store_id = ?", stores.storeFor(actor));
    }

    public String accessToken(UUID store) {
        TokenCipher cipher = new TokenCipher(encryptionKey);
        Claim claim = transactions.execute(status -> {
            List<Connection> rows = jdbc.query("""
                    SELECT generation, status, access_token, refresh_token,
                           access_expires_at > clock_timestamp() + INTERVAL '60 seconds' AS fresh
                    FROM melhor_envio_connections WHERE store_id = ? FOR UPDATE
                    """, (rs, row) -> new Connection(rs.getObject(1, UUID.class), rs.getString(2),
                    rs.getBytes(3), rs.getBytes(4), rs.getBoolean(5)), store);
            if (rows.isEmpty()) {
                return null;
            }
            Connection connection = rows.getFirst();
            boolean refresh = "CONNECTED".equals(connection.status()) && !connection.fresh();
            if (refresh) {
                jdbc.update("UPDATE melhor_envio_connections SET status = 'REFRESHING' WHERE store_id = ?", store);
            }
            return new Claim(connection, refresh);
        });
        if (claim != null && "REFRESHING".equals(claim.connection().status())) {
            throw new MelhorEnvioException(Reason.UNAVAILABLE);
        }
        if (claim == null || !"CONNECTED".equals(claim.connection().status())) {
            throw new MelhorEnvioException(Reason.AUTHENTICATION);
        }
        Connection connection = claim.connection();
        if (!claim.refresh()) {
            return cipher.decrypt(store, connection.access());
        }
        try {
            var tokens = client.refresh(cipher.decrypt(store, connection.refresh()));
            int updated = jdbc.update("""
                    UPDATE melhor_envio_connections SET status = 'CONNECTED', access_token = ?, refresh_token = ?,
                        access_expires_at = clock_timestamp() + (? * INTERVAL '1 second')
                    WHERE store_id = ? AND generation = ? AND status = 'REFRESHING'
                    """, cipher.encrypt(store, tokens.accessToken()), cipher.encrypt(store, tokens.refreshToken()),
                    tokens.expiresIn(), store, connection.generation());
            if (updated != 1) {
                throw new MelhorEnvioException(Reason.AUTHENTICATION);
            }
            return tokens.accessToken();
        } catch (MelhorEnvioException | IllegalStateException exception) {
            markReauthorize(store, connection.generation(), "REFRESHING");
            throw exception;
        }
    }

    private void markReauthorize(UUID store, UUID generation, String expected) {
        jdbc.update("""
                UPDATE melhor_envio_connections SET status = 'REAUTHORIZE', access_token = NULL, refresh_token = NULL,
                    access_expires_at = NULL WHERE store_id = ? AND generation = ? AND status = ?
                """, store, generation, expected);
    }

    private static boolean invalid(String value) {
        return value == null || value.isBlank() || value.length() > 4096;
    }
}
