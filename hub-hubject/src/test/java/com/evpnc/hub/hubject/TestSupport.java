package com.evpnc.hub.hubject;

import com.evpnc.hub.core.AuthorizationPolicy;
import com.evpnc.hub.core.AuthorizeDecision;
import com.evpnc.hub.core.MutableClock;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

final class TestSupport {
    static final AuthorizationPolicy ALLOW = request -> new AuthorizeDecision.Allowed();

    private TestSupport() {}

    static MutableClock newClock() {
        return new MutableClock();
    }

    /** The pinned contracts, checked out by tools/fetch-contracts.sh (CI) or pointed at with CONTRACTS_DIR. */
    static final class PinnedContracts {
        private static Map<String, Object> oicpEmp;

        private PinnedContracts() {}

        @SuppressWarnings("unchecked")
        static synchronized Map<String, Object> oicpEmp() {
            if (oicpEmp == null) {
                String configured = System.getenv("CONTRACTS_DIR");
                File dir = new File(configured != null ? configured : ".contracts");
                File file = new File(dir, "_sources/hubject-oicp-2.3-emp.openapi.yaml");
                if (!file.exists()) {
                    throw new IllegalStateException("pinned contracts not found at " + dir.getAbsolutePath()
                            + ". Run tools/fetch-contracts.sh or set CONTRACTS_DIR to a checkout of EV_PNC_hub-contracts.");
                }
                LoaderOptions options = new LoaderOptions();
                options.setCodePointLimit(64 * 1024 * 1024);
                try {
                    oicpEmp = (Map<String, Object>) new Yaml(options).load(Files.readString(file.toPath()));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            return oicpEmp;
        }
    }
}
