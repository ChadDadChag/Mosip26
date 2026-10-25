package io.mosip.registration.liveness.analyzer.integrity;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Public keys allowed to sign model bundles, by keyId. The built-in keys are compiled into this class on purpose:
 * a key read from a resource or a file next to the models could be swapped together with the models.
 *
 * <p><b>DEV KEY: replace with the production signing key before release; see docs.</b> The built-in key below is a
 * development key whose private half lives only on the developer's machine. Hosts that ship their own release key
 * use {@link #TrustedKeys(Map)}.
 */
public final class TrustedKeys {

    /** {keyId, hex X.509 SubjectPublicKeyInfo (EC P-256)}. DEV KEY: replace before release. */
    private static final String[][] BUILT_IN = {
        {"mosip-liveness-dev-2026-10",
            "3059301306072a8648ce3d020106082a8648ce3d03010703420004784647a03641df9434091fffc1ed39cacf94fc5f3c9997a929"
            + "31993e279d99e28c1fa38db8debab8ffc48a8a5b81e490049c433ec6fd2f8bb97c5265a57de3f7"},
    };

    private final Map<String, PublicKey> keys;

    public TrustedKeys(Map<String, PublicKey> keys) {
        if (keys == null) throw new IllegalArgumentException("keys is null");
        this.keys = Collections.unmodifiableMap(new LinkedHashMap<String, PublicKey>(keys));
    }

    /** The keys compiled into this class. */
    public static TrustedKeys builtIn() {
        Map<String, PublicKey> m = new LinkedHashMap<String, PublicKey>();
        for (String[] k : BUILT_IN) m.put(k[0], parsePublicKey(k[1]));
        return new TrustedKeys(m);
    }

    /** An EC public key from the lowercase hex of its X.509 SubjectPublicKeyInfo DER encoding. */
    public static PublicKey parsePublicKey(String hexX509) {
        try {
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Hex.decode(hexX509)));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("not an EC public key", e);
        }
    }

    /** The key for this id, or null. */
    public PublicKey get(String keyId) {
        return keys.get(keyId);
    }

    public Set<String> keyIds() {
        return keys.keySet();
    }
}
