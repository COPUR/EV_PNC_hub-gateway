package com.evpnc.hub.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Records requests and answers from a supplier, so tests read as "the hub says X, we do Y". */
public final class FakeTransport implements HubTransport {
    public final List<HttpRequestSpec> sent = new ArrayList<>();
    public Supplier<HttpResponseSpec> next = () -> {
        throw new IllegalStateException("no answer queued");
    };

    @Override
    public HttpResponseSpec send(HttpRequestSpec request) {
        sent.add(request);
        return next.get();
    }

    public void answers(int status, String body) {
        next = () -> new HttpResponseSpec(status, body);
    }
}
