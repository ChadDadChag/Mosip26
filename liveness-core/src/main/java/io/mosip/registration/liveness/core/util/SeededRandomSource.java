package io.mosip.registration.liveness.core.util;

import java.util.Random;

/** Deterministic randomness for tests and trace replay. Never use in production: sequences are predictable. */
public final class SeededRandomSource implements RandomSource {
    private final Random random;

    public SeededRandomSource(long seed) {
        this.random = new Random(seed);
    }

    @Override
    public synchronized int nextInt(int bound) {
        return random.nextInt(bound);
    }
}
