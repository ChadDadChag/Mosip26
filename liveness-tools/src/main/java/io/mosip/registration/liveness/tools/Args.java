package io.mosip.registration.liveness.tools;

import java.util.HashMap;
import java.util.Map;

/** "--key value" command-line arguments. */
final class Args {
    private final Map<String, String> values = new HashMap<String, String>();

    Args(String[] args) {
        for (int i = 0; i + 1 < args.length; i += 2) values.put(args[i], args[i + 1]);
    }

    String get(String key, String def) {
        String v = values.get(key);
        return v != null ? v : def;
    }

    String required(String key) {
        String v = values.get(key);
        if (v == null) throw new IllegalArgumentException("missing " + key);
        return v;
    }
}
