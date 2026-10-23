package io.mosip.registration.liveness.core.passive;

import java.util.Arrays;
import java.util.Collection;

/** Small numeric helpers. */
public final class Stats {
    private Stats() { }

    /** Median of the non-NaN values; NaN if there are none. */
    public static float median(Collection<Float> values) {
        float[] v = new float[values.size()];
        int n = 0;
        for (Float f : values) if (f != null && !Float.isNaN(f)) v[n++] = f;
        if (n == 0) return Float.NaN;
        Arrays.sort(v, 0, n);
        return n % 2 == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2f;
    }
}
