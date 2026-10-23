package io.mosip.registration.liveness.core.util;

import java.security.SecureRandom;

/** Production randomness. Thread-safe. */
public final class SecureRandomSource implements RandomSource {
    private final SecureRandom random = new SecureRandom();

    @Override
    public int nextInt(int bound) {
        return random.nextInt(bound);
    }
}
