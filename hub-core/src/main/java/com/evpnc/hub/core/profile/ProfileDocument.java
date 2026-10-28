package com.evpnc.hub.core.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * A hub profile from EV_PNC_hub-contracts, read as data (ADR-0001). Adapters read rules through typed views built on
 * this class; they do not hard-code hub behaviour. Paths are dotted: {@code timeouts.authorize_start_response_s.value}.
 */
public final class ProfileDocument {
    private final String name;
    private final Map<String, Object> root;

    public ProfileDocument(String name, Map<String, Object> root) {
        this.name = name;
        this.root = root;
    }

    public String name() {
        return name;
    }

    /** The raw value at {@code path} (a Map, List, scalar), or null. */
    public Object node(String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else if (current instanceof List<?> list) {
                current = indexOf(list, part);
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static Object indexOf(List<?> list, String part) {
        try {
            int i = Integer.parseInt(part);
            return i >= 0 && i < list.size() ? list.get(i) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean has(String path) {
        return node(path) != null;
    }

    public String string(String path) {
        String value = optionalString(path);
        if (value == null) {
            throw missing(path);
        }
        return value;
    }

    public String optionalString(String path) {
        Object value = node(path);
        return value == null ? null : value.toString();
    }

    public int integer(String path) {
        if (node(path) instanceof Number number) {
            return number.intValue();
        }
        throw missing(path);
    }

    public boolean bool(String path) {
        if (node(path) instanceof Boolean value) {
            return value;
        }
        throw missing(path);
    }

    public List<String> stringList(String path) {
        if (node(path) instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.toList());
        }
        throw missing(path);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String path) {
        if (node(path) instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw missing(path);
    }

    /** The {@code confidence} tag on the node itself, or on the nearest ancestor that has one; null when none. */
    public Confidence confidence(String path) {
        String[] parts = path.split("\\.");
        for (int length = parts.length; length >= 0; length--) {
            Object parent = length == 0 ? root : node(String.join(".", java.util.Arrays.copyOfRange(parts, 0, length)));
            if (parent instanceof Map<?, ?> map && map.get("confidence") instanceof String tag) {
                return parseConfidence(tag);
            }
        }
        return null;
    }

    /** Paths of every entry tagged {@code unknown}: what a mock must not pretend to know and a real hub must still be asked. */
    public List<String> unknownPaths() {
        List<String> out = new ArrayList<>();
        collectUnknown(root, "", out);
        return out;
    }

    private void collectUnknown(Object node, String path, List<String> out) {
        if (node instanceof Map<?, ?> map) {
            Object tag = map.get("confidence");
            if (tag instanceof String s && parseConfidence(s) == Confidence.UNKNOWN) {
                out.add(path.isEmpty() ? "<root>" : path);
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!"confidence".equals(entry.getKey())) {
                    collectUnknown(entry.getValue(), path.isEmpty() ? String.valueOf(entry.getKey()) : path + "." + entry.getKey(), out);
                }
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                collectUnknown(list.get(i), path + "." + i, out);
            }
        }
    }

    private IllegalStateException missing(String path) {
        return new IllegalStateException("profile '" + name + "' has no value at '" + path + "'");
    }

    private static Confidence parseConfidence(String text) {
        if (text == null) {
            return null;
        }
        if (text.startsWith("fact")) {
            return Confidence.FACT;
        }
        if (text.startsWith("inference")) {
            return Confidence.INFERENCE;
        }
        if (text.startsWith("unknown")) {
            return Confidence.UNKNOWN;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static ProfileDocument parse(String name, String yaml) {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(4 * 1024 * 1024);
        Object loaded = new Yaml(options).load(yaml);
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("profile '" + name + "' is not a YAML mapping");
        }
        return new ProfileDocument(name, (Map<String, Object>) map);
    }
}
