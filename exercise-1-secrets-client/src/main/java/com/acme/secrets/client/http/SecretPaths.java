package com.acme.secrets.client.http;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Request URIs of the Secrets Manager REST API.
 */
public final class SecretPaths {

    private SecretPaths() {
    }

    public static URI secretUri(URI baseUri, String secretId) {
        return URI.create(withoutTrailingSlash(baseUri) + "/v1/secrets/" + encodePathSegment(secretId));
    }

    public static URI tokenUri(URI baseUri) {
        return URI.create(withoutTrailingSlash(baseUri) + "/v1/auth/token");
    }

    private static String encodePathSegment(String value) {
        // URLEncoder applies form encoding, which turns a space into '+'; a path segment needs %20.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String withoutTrailingSlash(URI baseUri) {
        String base = baseUri.toString();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
