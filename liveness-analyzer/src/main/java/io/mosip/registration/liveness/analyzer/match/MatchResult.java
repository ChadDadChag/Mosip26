package io.mosip.registration.liveness.analyzer.match;

/** The result of {@link FaceMatcher#compare}: an outcome, the cosine similarity (NaN unless both faces were found) and
 * the threshold it was compared with. Holds no image or feature. */
public final class MatchResult {

    public enum Outcome {
        /** One face in each image, similarity at or above the threshold: the same person. */
        MATCH,
        /** One face in each image, similarity below the threshold. */
        NO_MATCH,
        NO_FACE_A, NO_FACE_B,
        MULTIPLE_FACES_A, MULTIPLE_FACES_B
    }

    public final Outcome outcome;
    public final double similarity;
    public final double threshold;

    MatchResult(Outcome outcome, double similarity, double threshold) {
        this.outcome = outcome;
        this.similarity = similarity;
        this.threshold = threshold;
    }

    public boolean matched() {
        return outcome == Outcome.MATCH;
    }

    @Override
    public String toString() {
        return outcome + " (similarity " + similarity + ", threshold " + threshold + ")";
    }
}
