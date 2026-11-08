package com.gabrielsena.commerce.auth.config;

import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    SecretKey jwtSecretKey(@Value("${security.jwt.secret}") String encodedSecret) {
        byte[] keyBytes = Base64.getDecoder().decode(encodedSecret);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("JWT signing key must contain at least 256 bits");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(jwtSecretKey));
    }

    @Bean
    JwtDecoder jwtDecoder(
            SecretKey jwtSecretKey,
            @Value("${spring.application.name}") String issuer
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/merchants").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/public/customer-auth/register",
                                "/api/public/customer-auth/login", "/api/public/customer-auth/refresh",
                                "/api/public/customer-auth/logout").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/login",
                                "/api/auth/refresh",
                                "/api/auth/logout"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/public/catalog/products",
                                "/api/public/catalog/products/{productId}",
                                "/api/public/catalog/products/{productId}/variants").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/public/pickup").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/public/integrations/melhor-envio/callback").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/public/cart").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/public/cart/shipping-estimates",
                                "/api/public/catalog/products/{productId}/variants/{variantId}/shipping-estimates").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/public/cart").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/api/public/cart/items/{productId}/{variantId}").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/public/cart/items/{productId}/{variantId}").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/public/checkout",
                                "/api/public/checkout/{reservationId}/cancel").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/public/checkout").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/public/checkout/{reservationId}/orders").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/public/orders/{orderId}").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/public/customer/orders",
                                "/api/public/customer/orders/{orderId}").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/public/customer/profile", "/api/public/customer/addresses",
                                "/api/public/customer/addresses/{id}").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/public/customer/profile",
                                "/api/public/customer/addresses/{id}").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/public/customer/addresses").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/public/customer/addresses/{id}").authenticated()
                        .anyRequest().access((authentication, context) -> {
                            var auth = authentication.get();
                            return new AuthorizationDecision(auth instanceof JwtAuthenticationToken jwt
                                    && jwt.isAuthenticated()
                                    && (jwt.getToken().getClaimAsString("account_type") == null
                                        || "merchant".equals(jwt.getToken().getClaimAsString("account_type")))
                                    && jwt.getToken().getClaimAsString("merchant_id") != null);
                        })
                )
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
                .build();
    }
}
