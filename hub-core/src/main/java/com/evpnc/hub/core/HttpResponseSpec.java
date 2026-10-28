package com.evpnc.hub.core;

import java.util.Map;

public record HttpResponseSpec(int status, String body, Map<String, String> headers) {

    public HttpResponseSpec {
        headers = Map.copyOf(headers);
    }

    public HttpResponseSpec(int status, String body) {
        this(status, body, Map.of());
    }

    public HttpResponseSpec(int status) {
        this(status, null, Map.of());
    }
}
