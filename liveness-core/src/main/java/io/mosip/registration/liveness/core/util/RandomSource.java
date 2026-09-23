package io.mosip.registration.liveness.core.util;

/**
 * Randomness used for challenge selection and prompt jitter. Production uses {@link SecureRandomSource}
 * so the challenge sequence cannot be predicted; tests use {@link SeededRandomSource}.
 */
public interface RandomSource {
    /** Uniform int in [0, bound). */
    int nextInt(int bound);
}
