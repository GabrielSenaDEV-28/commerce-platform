package com.gabrielsena.commerce.catalog.application;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class S3ProductPhotoStorageTest {
    @Test
    void shouldSendUploadAndDeleteToConfiguredS3Endpoint() throws Exception {
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        String oldAccess = System.getProperty("aws.accessKeyId");
        String oldSecret = System.getProperty("aws.secretAccessKey");
        System.setProperty("aws.accessKeyId", "test-access");
        System.setProperty("aws.secretAccessKey", "test-secret");
        server.start();
        try {
            try (var storage = new S3ProductPhotoStorage("photos", "us-east-1",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "https://cdn.example.test")) {
                storage.put("products/store/product/photo", "image/png", new byte[]{1, 2, 3});
                storage.delete("products/store/product/photo");
                assertEquals(List.of("PUT /photos/products/store/product/photo",
                        "DELETE /photos/products/store/product/photo"), requests);
                assertEquals("https://cdn.example.test/products/store/product/photo",
                        storage.url("products/store/product/photo"));
            }
        } finally {
            server.stop(0);
            restoreProperty("aws.accessKeyId", oldAccess);
            restoreProperty("aws.secretAccessKey", oldSecret);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
