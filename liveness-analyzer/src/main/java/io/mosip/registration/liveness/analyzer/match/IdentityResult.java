package io.mosip.registration.liveness.analyzer.match;

import java.util.Locale;

/**
 * The result of {@link FaceMatcher#identify} (F15): is the live face the user whose onboarded face templates were
 * given? An outcome, the best similarity over the readable templates (NaN when nothing was compared), the threshold, and
 * how many templates were given and could not be read. Holds no image or feature.
 */
public final class IdentityResult {

    public enum Outcome {
        /** The best similarity over the readable templates is at or above the threshold. */
        MATCH,
        /** At least one template was readable, and every readable one is below the threshold. */
        NO_MATCH,
        /** No template was given: the user has no onboarded face on this device. */
        NOT_BOUND_NO_TEMPLATE,
        /** Templates were given, but none decoded to an image with exactly one face. */
        NOT_BOUND_UNREADABLE,
        /** The live image does not hold exactly one face (not expected after a PASSED liveness session). */
        NO_LIVE_FACE
    }

    public final Outcome outcome;
    public final double similarity;
    public final double threshold;
    /** Templates given, and how many of them were undecodable or held no face or several faces. */
    public final int templates, unreadable;

    IdentityResult(Outcome outcome, double similarity, double threshold, int templates, int unreadable) {
        this.outcome = outcome;
        this.similarity = similarity;
        this.threshold = threshold;
        this.templates = templates;
        this.unreadable = unreadable;
    }

    public boolean matched() {
        return outcome == Outcome.MATCH;
    }

    /** True for the two outcomes where the user has no usable onboarded face, so nothing could be compared. */
    public boolean notBound() {
        return outcome == Outcome.NOT_BOUND_NO_TEMPLATE || outcome == Outcome.NOT_BOUND_UNREADABLE;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s (similarity %.3f, threshold %.2f, %d template(s), %d unreadable)", outcome,
                similarity, threshold, templates, unreadable);
    }
}
