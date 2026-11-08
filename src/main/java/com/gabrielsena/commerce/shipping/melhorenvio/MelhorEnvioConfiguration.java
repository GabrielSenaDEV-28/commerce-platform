package com.gabrielsena.commerce.shipping.melhorenvio;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class MelhorEnvioConfiguration {
    @Bean
    MelhorEnvioClient melhorEnvioClient(@Value("${shipping.melhor-envio.user-agent:}") String userAgent) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        RestClient client = RestClient.builder().baseUrl("https://sandbox.melhorenvio.com.br")
                .requestFactory(factory).build();
        return new MelhorEnvioClient(client, userAgent);
    }
}
