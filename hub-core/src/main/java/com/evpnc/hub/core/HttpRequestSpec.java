package com.evpnc.hub.core;

import java.util.Map;

/** Minimal HTTP seam. Adapters build requests as data; a transport (mTLS client, test fake) executes them. */
public record HttpRequestSpec(String method, String path, Map<String, String> headers, Map<String, String> query, String body) {

    public HttpRequestSpec {
        headers = Map.copyOf(headers);
        query = Map.copyOf(query);
    }

    public HttpRequestSpec(String method, String path) {
        this(method, path, Map.of(), Map.of(), null);
    }

    public HttpRequestSpec(String method, String path, Map<String, String> headers, String body) {
        this(method, path, headers, Map.of(), body);
    }
}
