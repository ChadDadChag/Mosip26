package io.mosip.registration.liveness.analyzer.pad;

/** Output of {@link SilentFacePad#assess}: both models' softmax vectors, the fused score and the crop boxes. */
public final class PadResult {

    /** Softmax of MiniFASNetV2 (crop scale 2.7); index 1 = real. */
    public final float[] v2;
    /** Softmax of MiniFASNetV1SE (crop scale 4.0); index 1 = real. */
    public final float[] v1se;
    /** (v2[1] + v1se[1]) / 2. */
    public final float score;
    /** Inclusive crop boxes {ltx, lty, rbx, rby} fed to each model. */
    public final int[] v2Box, v1seBox;

    public PadResult(float[] v2, float[] v1se, float score, int[] v2Box, int[] v1seBox) {
        this.v2 = v2;
        this.v1se = v1se;
        this.score = score;
        this.v2Box = v2Box;
        this.v1seBox = v1seBox;
    }
}
