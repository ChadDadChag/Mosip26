package io.mosip.registration.liveness.analyzer.image;

/**
 * Immutable 2x3 affine map from DESTINATION continuous coordinates to SOURCE continuous coordinates
 * ({@code reference_pipeline.py}): pixel (col i, row j) covers [i, i+1) x [j, j+1), so its centre is
 * (i + 0.5, j + 0.5).
 * <pre>
 *   srcX = a*x + b*y + c
 *   srcY = d*x + e*y + f
 * </pre>
 */
public final class Affine {

    public final double a, b, c, d, e, f;

    public Affine(double a, double b, double c, double d, double e, double f) {
        this.a = a; this.b = b; this.c = c; this.d = d; this.e = e; this.f = f;
    }

    /** Plain resize of a srcW x srcH image to dstW x dstH (the half-pixel rule src = (dst + 0.5) * scale - 0.5). */
    public static Affine scale(int srcW, int srcH, int dstW, int dstH) {
        return new Affine((double) srcW / dstW, 0, 0, 0, (double) srcH / dstH, 0);
    }

    /** Inclusive pixel crop cols left..left+cropW-1, rows top..top+cropH-1, resized to outW x outH. */
    public static Affine crop(int left, int top, int cropW, int cropH, int outW, int outH) {
        return new Affine((double) cropW / outW, 0, left, 0, (double) cropH / outH, top);
    }

    /**
     * Square of side {@code side} centred on (cx, cy), rotated by {@code theta} radians, sampled to
     * outSize x outSize ({@code reference_pipeline.mesh_matrix}):
     * <pre>
     *   dx = (u/N - 0.5)*S   dy = (v/N - 0.5)*S
     *   X  = cx + cos*dx - sin*dy   Y = cy + sin*dx + cos*dy
     * </pre>
     */
    public static Affine rotatedSquare(double cx, double cy, double side, double theta, int outSize) {
        double cos = Math.cos(theta), sin = Math.sin(theta);
        double k = side / outSize;
        return new Affine(cos * k, -sin * k, cx - cos * side / 2 + sin * side / 2,
                sin * k, cos * k, cy - sin * side / 2 - cos * side / 2);
    }

    public double mapX(double x, double y) {
        return a * x + b * y + c;
    }

    public double mapY(double x, double y) {
        return d * x + e * y + f;
    }
}
