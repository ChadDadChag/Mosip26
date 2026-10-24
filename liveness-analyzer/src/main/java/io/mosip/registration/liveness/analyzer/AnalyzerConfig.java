package io.mosip.registration.liveness.analyzer;

import io.mosip.registration.liveness.analyzer.detect.DetectorConfig;
import io.mosip.registration.liveness.api.ConfigProvider;

/**
 * Settings of {@link OnnxFaceAnalyzer}. Immutable; build with {@link #builder()} or read from the host config with
 * {@link #fromConfig(ConfigProvider)}. Defaults match {@code models/tools/reference_pipeline.py}.
 *
 * <p>Optional config keys (all prefixed {@value #KEY_PREFIX}); a missing key keeps the default. They are NOT in
 * {@code liveness-default.properties} (owned by Part 3):
 * <ul>
 *   <li>{@code intraOpThreads} (2), {@code accelerator} (CPU | XNNPACK | NNAPI, default CPU)</li>
 *   <li>{@code detectorMaxWidth} (320), {@code detectorScoreThreshold} (0.7), {@code detectorNmsIou} (0.3),
 *       {@code detectorSecondFaceMinAreaRatio} (0.2)</li>
 *   <li>{@code meshTracking} (true: the mesh ROI follows the previous frame's landmarks, as MediaPipe tracks, F7),
 *       {@code meshPresenceThreshold} (0.5), {@code meshRoiScale} (1.5), {@code pitchBiasDeg} (-9.5; when the key is
 *       absent the model bundle's signed param wins, see {@link #pitchBiasExplicit})</li>
 *   <li>{@code padMaxYawDeg} (30), {@code padMaxPitchDeg} (25): outside this pose range the PAD score is NaN</li>
 *   <li>{@code tooDarkLuma} (40), {@code tooBrightLuma} (220), {@code blurLapVar} (15), {@code minAreaFrac} (0.04),
 *       {@code minFaceWidthPx} (80), {@code maxAreaFrac} (0.60), {@code maxCentreOffset} (0.25),
 *       {@code qualityMaxClippedFraction} (0.30: a larger share of blown-out face pixels is TOO_BRIGHT)</li>
 *   <li>session moire cap ({@link io.mosip.registration.liveness.analyzer.pad.MoireCap}): {@code moireCapThreshold}
 *       (1.40; {@code off} disables), {@code moireCapScore} (0.60), {@code moireWindow} (10), {@code moireMinFrames} (3),
 *       {@code moireVetoThreshold} ({@code off}; a number enables the hard veto)</li>
 *   <li>relative motion blur ({@link io.mosip.registration.liveness.analyzer.quality.RelativeBlur}):
 *       {@code qualityRelativeBlurRatio} (0.45; {@code off} disables): BLURRY also when lapVar is below this
 *       fraction of the session's rolling median</li>
 * </ul>
 * Face binding (F1) has its own key, outside the analyzer prefix: {@value #BINDING_THRESHOLD_KEY} (0.363, OpenCV's
 * SFace cosine threshold; {@link io.mosip.registration.liveness.analyzer.match.FaceMatcher}).
 */
public final class AnalyzerConfig {

    public static final String KEY_PREFIX = "mosip.registration.liveness.face.analyzer.";
    public static final String BINDING_THRESHOLD_KEY = "mosip.registration.liveness.face.binding.cosineThreshold";

    /** ONNX Runtime execution provider. Unavailable providers fall back to CPU. */
    public enum Accelerator { CPU, XNNPACK, NNAPI }

    public final int intraOpThreads;
    public final Accelerator accelerator;
    public final DetectorConfig detector;
    public final double meshPresenceThreshold;
    public final double meshRoiScale;
    public final double pitchBiasDeg;
    /**
     * True when the host set pitchBiasDeg itself (builder call, or the config key is present). Otherwise the model
     * bundle's signed {@code params.pitchBiasDeg} replaces the default, because the bias belongs to the mesh model.
     */
    public final boolean pitchBiasExplicit;
    public final double padMaxYawDeg;
    public final double padMaxPitchDeg;
    public final double tooDarkLuma;
    public final double tooBrightLuma;
    public final double blurLapVar;
    public final double minAreaFrac;
    public final double minFaceWidthPx;
    public final double maxAreaFrac;
    public final double maxCentreOffset;
    /** Share of the face crop with R, G and B all >= 250 above which the face is TOO_BRIGHT. */
    public final double qualityMaxClippedFraction;
    /** Session moire cap: rolling median at or above this caps the score; NaN = off. */
    public final double moireCapThreshold;
    /** The capped score, in (0, 1): below passiveThreshold, above padRejectThreshold. */
    public final double moireCapScore;
    /** Single-face frames in the rolling moire median. */
    public final int moireWindow;
    /** Values needed in the window before the cap or veto can apply. */
    public final int moireMinFrames;
    /** Rolling median at or above this sets the score to 0; NaN = off (the default until multi-device data). */
    public final double moireVetoThreshold;
    /** Face binding: two faces are the same person when the SFace cosine is at or above this, in [-1, 1]. */
    public final double bindingCosineThreshold;
    /** BLURRY also below this fraction of the session's rolling lapVar median, in (0, 1]; NaN = off. */
    public final double qualityRelativeBlurRatio;
    /** Mesh ROI from the previous frame's landmarks when the face box overlaps (IoU >= 0.3); else from YuNet. */
    public final boolean meshTracking;

    private AnalyzerConfig(Builder b) {
        intraOpThreads = b.intraOpThreads;
        accelerator = b.accelerator;
        detector = b.detector;
        meshPresenceThreshold = b.meshPresenceThreshold;
        meshRoiScale = b.meshRoiScale;
        pitchBiasDeg = b.pitchBiasDeg;
        pitchBiasExplicit = b.pitchBiasExplicit;
        padMaxYawDeg = b.padMaxYawDeg;
        padMaxPitchDeg = b.padMaxPitchDeg;
        tooDarkLuma = b.tooDarkLuma;
        tooBrightLuma = b.tooBrightLuma;
        blurLapVar = b.blurLapVar;
        minAreaFrac = b.minAreaFrac;
        minFaceWidthPx = b.minFaceWidthPx;
        maxAreaFrac = b.maxAreaFrac;
        maxCentreOffset = b.maxCentreOffset;
        qualityMaxClippedFraction = b.qualityMaxClippedFraction;
        moireCapThreshold = b.moireCapThreshold;
        moireCapScore = b.moireCapScore;
        moireWindow = b.moireWindow;
        moireMinFrames = b.moireMinFrames;
        moireVetoThreshold = b.moireVetoThreshold;
        bindingCosineThreshold = b.bindingCosineThreshold;
        qualityRelativeBlurRatio = b.qualityRelativeBlurRatio;
        meshTracking = b.meshTracking;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static AnalyzerConfig defaults() {
        return new Builder().build();
    }

    /** A builder pre-filled with this config's values. */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.intraOpThreads = intraOpThreads;
        b.accelerator = accelerator;
        b.detector = detector;
        b.meshPresenceThreshold = meshPresenceThreshold;
        b.meshRoiScale = meshRoiScale;
        b.pitchBiasDeg = pitchBiasDeg;
        b.pitchBiasExplicit = pitchBiasExplicit;
        b.padMaxYawDeg = padMaxYawDeg;
        b.padMaxPitchDeg = padMaxPitchDeg;
        b.tooDarkLuma = tooDarkLuma;
        b.tooBrightLuma = tooBrightLuma;
        b.blurLapVar = blurLapVar;
        b.minAreaFrac = minAreaFrac;
        b.minFaceWidthPx = minFaceWidthPx;
        b.maxAreaFrac = maxAreaFrac;
        b.maxCentreOffset = maxCentreOffset;
        b.qualityMaxClippedFraction = qualityMaxClippedFraction;
        b.moireCapThreshold = moireCapThreshold;
        b.moireCapScore = moireCapScore;
        b.moireWindow = moireWindow;
        b.moireMinFrames = moireMinFrames;
        b.moireVetoThreshold = moireVetoThreshold;
        b.bindingCosineThreshold = bindingCosineThreshold;
        b.qualityRelativeBlurRatio = qualityRelativeBlurRatio;
        b.meshTracking = meshTracking;
        return b;
    }

    /**
     * Reads the optional {@value #KEY_PREFIX}* keys; missing keys keep the defaults.
     *
     * @throws IllegalArgumentException naming the key, for an unparseable or out-of-range value
     */
    public static AnalyzerConfig fromConfig(ConfigProvider cfg) {
        Builder b = new Builder();
        if (cfg == null) return b.build();
        b.intraOpThreads = intValue(cfg, "intraOpThreads", b.intraOpThreads);
        String acc = cfg.get(KEY_PREFIX + "accelerator");
        if (acc != null && !acc.trim().isEmpty()) {
            try {
                b.accelerator = Accelerator.valueOf(acc.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(KEY_PREFIX + "accelerator: unknown value '" + acc + "'");
            }
        }
        DetectorConfig d = b.detector;
        int maxWidth = intValue(cfg, "detectorMaxWidth", d.maxWidth);
        double score = doubleValue(cfg, "detectorScoreThreshold", d.scoreThreshold);
        double iou = doubleValue(cfg, "detectorNmsIou", d.nmsIou);
        double ratio = doubleValue(cfg, "detectorSecondFaceMinAreaRatio", d.secondFaceMinAreaRatio);
        if (maxWidth < 32) throw new IllegalArgumentException(KEY_PREFIX + "detectorMaxWidth must be >= 32");
        b.detector = new DetectorConfig(maxWidth, score, iou, ratio);
        b.meshPresenceThreshold = doubleValue(cfg, "meshPresenceThreshold", b.meshPresenceThreshold);
        b.meshRoiScale = doubleValue(cfg, "meshRoiScale", b.meshRoiScale);
        String bias = cfg.get(KEY_PREFIX + "pitchBiasDeg");
        if (bias != null && !bias.trim().isEmpty()) b.pitchBiasDeg(doubleValue(cfg, "pitchBiasDeg", b.pitchBiasDeg));
        b.padMaxYawDeg = doubleValue(cfg, "padMaxYawDeg", b.padMaxYawDeg);
        b.padMaxPitchDeg = doubleValue(cfg, "padMaxPitchDeg", b.padMaxPitchDeg);
        b.tooDarkLuma = doubleValue(cfg, "tooDarkLuma", b.tooDarkLuma);
        b.tooBrightLuma = doubleValue(cfg, "tooBrightLuma", b.tooBrightLuma);
        b.blurLapVar = doubleValue(cfg, "blurLapVar", b.blurLapVar);
        b.minAreaFrac = doubleValue(cfg, "minAreaFrac", b.minAreaFrac);
        b.minFaceWidthPx = doubleValue(cfg, "minFaceWidthPx", b.minFaceWidthPx);
        b.maxAreaFrac = doubleValue(cfg, "maxAreaFrac", b.maxAreaFrac);
        b.maxCentreOffset = doubleValue(cfg, "maxCentreOffset", b.maxCentreOffset);
        b.qualityMaxClippedFraction = doubleValue(cfg, "qualityMaxClippedFraction", b.qualityMaxClippedFraction);
        b.moireCapThreshold = optionalThreshold(cfg, "moireCapThreshold", b.moireCapThreshold);
        b.moireCapScore = doubleValue(cfg, "moireCapScore", b.moireCapScore);
        b.moireWindow = intValue(cfg, "moireWindow", b.moireWindow);
        b.moireMinFrames = intValue(cfg, "moireMinFrames", b.moireMinFrames);
        b.moireVetoThreshold = optionalThreshold(cfg, "moireVetoThreshold", b.moireVetoThreshold);
        b.qualityRelativeBlurRatio = optionalThreshold(cfg, "qualityRelativeBlurRatio", b.qualityRelativeBlurRatio);
        String tracking = cfg.get(KEY_PREFIX + "meshTracking");
        if (tracking != null && !tracking.trim().isEmpty()) {
            String t = tracking.trim().toLowerCase(java.util.Locale.ROOT);
            if (!t.equals("true") && !t.equals("false")) {
                throw new IllegalArgumentException(KEY_PREFIX + "meshTracking: not true or false: '" + tracking + "'");
            }
            b.meshTracking = t.equals("true");
        }
        String binding = cfg.get(BINDING_THRESHOLD_KEY);
        if (binding != null && !binding.trim().isEmpty()) {
            try {
                b.bindingCosineThreshold = Double.parseDouble(binding.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(BINDING_THRESHOLD_KEY + ": not a number: '" + binding + "'");
            }
        }
        return b.build();
    }

    /**
     * Checks that every value is in a sane range.
     *
     * @throws IllegalArgumentException naming the offending key
     */
    public void validate() {
        range("intraOpThreads", intraOpThreads, 1, 64);
        if (accelerator == null) throw new IllegalArgumentException(KEY_PREFIX + "accelerator must not be null");
        if (detector == null) throw new IllegalArgumentException(KEY_PREFIX + "detector must not be null");
        openRange("detectorScoreThreshold", detector.scoreThreshold, 0, 1);
        openRange("detectorNmsIou", detector.nmsIou, 0, 1);
        range("detectorSecondFaceMinAreaRatio", detector.secondFaceMinAreaRatio, 0, 1);
        openRange("meshPresenceThreshold", meshPresenceThreshold, 0, 1);
        range("meshRoiScale", meshRoiScale, 1, 3);
        range("pitchBiasDeg", pitchBiasDeg, -30, 30);
        positiveUpTo("padMaxYawDeg", padMaxYawDeg, 90);
        positiveUpTo("padMaxPitchDeg", padMaxPitchDeg, 90);
        range("tooDarkLuma", tooDarkLuma, 0, 255);
        range("tooBrightLuma", tooBrightLuma, 0, 255);
        if (!(tooDarkLuma < tooBrightLuma)) {
            throw new IllegalArgumentException(KEY_PREFIX + "tooDarkLuma must be below " + KEY_PREFIX + "tooBrightLuma");
        }
        range("blurLapVar", blurLapVar, 0, 1e6);
        range("minAreaFrac", minAreaFrac, 0, 1);
        positiveUpTo("maxAreaFrac", maxAreaFrac, 1);
        if (!(minAreaFrac < maxAreaFrac)) {
            throw new IllegalArgumentException(KEY_PREFIX + "minAreaFrac must be below " + KEY_PREFIX + "maxAreaFrac");
        }
        range("minFaceWidthPx", minFaceWidthPx, 0, 10000);
        positiveUpTo("maxCentreOffset", maxCentreOffset, 0.5);
        positiveUpTo("qualityMaxClippedFraction", qualityMaxClippedFraction, 1);
        if (!Double.isNaN(moireCapThreshold)) positiveUpTo("moireCapThreshold", moireCapThreshold, 10);
        openRange("moireCapScore", moireCapScore, 0, 1);
        range("moireMinFrames", moireMinFrames, 1, 100);
        range("moireWindow", moireWindow, moireMinFrames, 100);
        if (!Double.isNaN(moireVetoThreshold)) positiveUpTo("moireVetoThreshold", moireVetoThreshold, 10);
        if (!Double.isNaN(qualityRelativeBlurRatio)) positiveUpTo("qualityRelativeBlurRatio", qualityRelativeBlurRatio, 1);
        if (!(bindingCosineThreshold >= -1 && bindingCosineThreshold <= 1)) {
            throw new IllegalArgumentException(BINDING_THRESHOLD_KEY + " must be in [-1.0, 1.0], got " + bindingCosineThreshold);
        }
    }

    private static void range(String key, double v, double lo, double hi) {
        if (!(v >= lo && v <= hi)) throw new IllegalArgumentException(KEY_PREFIX + key + " must be in [" + lo + ", " + hi + "], got " + v);
    }

    private static void openRange(String key, double v, double lo, double hi) {
        if (!(v > lo && v < hi)) throw new IllegalArgumentException(KEY_PREFIX + key + " must be in (" + lo + ", " + hi + "), got " + v);
    }

    private static void positiveUpTo(String key, double v, double hi) {
        if (!(v > 0 && v <= hi)) throw new IllegalArgumentException(KEY_PREFIX + key + " must be in (0, " + hi + "], got " + v);
    }

    private static int intValue(ConfigProvider cfg, String key, int dflt) {
        String s = cfg.get(KEY_PREFIX + key);
        if (s == null || s.trim().isEmpty()) return dflt;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(KEY_PREFIX + key + ": not an integer: '" + s + "'");
        }
    }

    /** A threshold that can be switched off: "off" (or "none", "nan") gives NaN, anything else must be a number. */
    private static double optionalThreshold(ConfigProvider cfg, String key, double dflt) {
        String s = cfg.get(KEY_PREFIX + key);
        if (s != null && s.trim().toLowerCase(java.util.Locale.ROOT).matches("off|none|nan")) return Double.NaN;
        return doubleValue(cfg, key, dflt);
    }

    private static double doubleValue(ConfigProvider cfg, String key, double dflt) {
        String s = cfg.get(KEY_PREFIX + key);
        if (s == null || s.trim().isEmpty()) return dflt;
        double v;
        try {
            v = Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(KEY_PREFIX + key + ": not a number: '" + s + "'");
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new IllegalArgumentException(KEY_PREFIX + key + ": not a finite number: '" + s + "'");
        }
        return v;
    }

    /** Mutable builder; {@link #build()} validates. */
    public static final class Builder {
        private int intraOpThreads = 2;
        private Accelerator accelerator = Accelerator.CPU;
        private DetectorConfig detector = DetectorConfig.defaults();
        private double meshPresenceThreshold = 0.5;
        private double meshRoiScale = 1.5;
        private double pitchBiasDeg = -9.5;
        private boolean pitchBiasExplicit;
        private double padMaxYawDeg = 30;
        private double padMaxPitchDeg = 25;
        private double tooDarkLuma = 40;
        private double tooBrightLuma = 220;
        private double blurLapVar = 15;
        private double minAreaFrac = 0.04;
        private double minFaceWidthPx = 80;
        private double maxAreaFrac = 0.60;
        private double maxCentreOffset = 0.25;
        private double qualityMaxClippedFraction = 0.30;
        private double moireCapThreshold = 1.40;
        private double moireCapScore = 0.60;
        private int moireWindow = 10;
        private int moireMinFrames = 3;
        private double moireVetoThreshold = Double.NaN;
        private double bindingCosineThreshold = 0.363;
        private double qualityRelativeBlurRatio = 0.45;
        private boolean meshTracking = true;

        private Builder() { }

        public Builder intraOpThreads(int v) { intraOpThreads = v; return this; }
        public Builder accelerator(Accelerator v) { accelerator = v; return this; }
        public Builder detector(DetectorConfig v) { detector = v; return this; }
        public Builder meshPresenceThreshold(double v) { meshPresenceThreshold = v; return this; }
        public Builder meshRoiScale(double v) { meshRoiScale = v; return this; }
        /** Sets the bias and marks it explicit, so a bundle's signed pitchBiasDeg param no longer replaces it. */
        public Builder pitchBiasDeg(double v) { pitchBiasDeg = v; pitchBiasExplicit = true; return this; }
        /** Lets a bundle param replace (false) or not replace (true) the current pitch bias. */
        public Builder pitchBiasExplicit(boolean v) { pitchBiasExplicit = v; return this; }
        public Builder padMaxYawDeg(double v) { padMaxYawDeg = v; return this; }
        public Builder padMaxPitchDeg(double v) { padMaxPitchDeg = v; return this; }
        public Builder tooDarkLuma(double v) { tooDarkLuma = v; return this; }
        public Builder tooBrightLuma(double v) { tooBrightLuma = v; return this; }
        public Builder blurLapVar(double v) { blurLapVar = v; return this; }
        public Builder minAreaFrac(double v) { minAreaFrac = v; return this; }
        public Builder minFaceWidthPx(double v) { minFaceWidthPx = v; return this; }
        public Builder maxAreaFrac(double v) { maxAreaFrac = v; return this; }
        public Builder maxCentreOffset(double v) { maxCentreOffset = v; return this; }
        public Builder qualityMaxClippedFraction(double v) { qualityMaxClippedFraction = v; return this; }
        /** NaN switches the cap off. */
        public Builder moireCapThreshold(double v) { moireCapThreshold = v; return this; }
        public Builder moireCapScore(double v) { moireCapScore = v; return this; }
        public Builder moireWindow(int v) { moireWindow = v; return this; }
        public Builder moireMinFrames(int v) { moireMinFrames = v; return this; }
        /** NaN (the default) switches the veto off. */
        public Builder moireVetoThreshold(double v) { moireVetoThreshold = v; return this; }
        public Builder bindingCosineThreshold(double v) { bindingCosineThreshold = v; return this; }
        /** NaN switches the relative blur rule off. */
        public Builder qualityRelativeBlurRatio(double v) { qualityRelativeBlurRatio = v; return this; }
        public Builder meshTracking(boolean v) { meshTracking = v; return this; }

        public AnalyzerConfig build() {
            AnalyzerConfig c = new AnalyzerConfig(this);
            c.validate();
            return c;
        }
    }
}
