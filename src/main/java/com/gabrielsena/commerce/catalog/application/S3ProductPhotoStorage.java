package com.gabrielsena.commerce.catalog.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;

import java.net.URI;
import java.time.Duration;

@Component
public class S3ProductPhotoStorage implements ProductPhotoStorage, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(S3ProductPhotoStorage.class);
    private final String bucket;
    private final String publicBaseUrl;
    private final S3Client client;

    public S3ProductPhotoStorage(@Value("${photos.s3.bucket:}") String bucket,
                                 @Value("${photos.s3.region:}") String region,
                                 @Value("${photos.s3.endpoint:}") String endpoint,
                                 @Value("${photos.public-base-url:}") String publicBaseUrl) {
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl;
        if (bucket.isBlank() || region.isBlank() || publicBaseUrl.isBlank()) {
            this.client = null;
        } else {
            var builder = S3Client.builder().region(Region.of(region)).forcePathStyle(!endpoint.isBlank())
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .apiCallTimeout(Duration.ofMinutes(2)).build());
            if (!endpoint.isBlank()) {
                builder.endpointOverride(URI.create(endpoint));
            }
            this.client = builder.build();
        }
    }

    @Override
    public void put(String key, String contentType, byte[] contents) {
        requireConfigured();
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
                    .contentLength((long) contents.length).build(), RequestBody.fromBytes(contents));
        } catch (SdkException exception) {
            log.error("Photo storage upload failed", exception);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Photo storage is unavailable.", exception);
        }
    }

    @Override
    public void delete(String key) {
        requireConfigured();
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException exception) {
            log.error("Photo storage deletion failed", exception);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Photo storage is unavailable.", exception);
        }
    }

    @Override
    public String url(String key) {
        requireConfigured();
        return publicBaseUrl.replaceAll("/+$", "") + "/" + key;
    }

    private void requireConfigured() {
        if (client == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Photo storage is not configured.");
        }
    }

    @Override
    @PreDestroy
    public void close() {
        if (client != null) {
            client.close();
        }
    }
}
