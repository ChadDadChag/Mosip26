package io.mosip.registration.liveness.core.challenge;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.core.util.RandomSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Picks an unpredictable challenge sequence. The user never chooses.
 * <ul>
 *   <li>Candidates = configured pool ∩ supported (by the analyzer/detectors), de-duplicated.</li>
 *   <li>No challenge repeats within a sequence.</li>
 *   <li>Opposite motions are never adjacent (TURN_LEFT/TURN_RIGHT, LOOK_UP/LOOK_DOWN): returning from one
 *       looks like the other to a naive detector.</li>
 *   <li>The full sequence differs from the previous attempt's sequence whenever an alternative exists.</li>
 * </ul>
 * Uniform over all valid sequences (rejection sampling over random permutations).
 */
public final class ChallengeSelector {

    // Rejection sampling bound; with at most 6 challenge types valid sequences are common, so this is never hit
    // in practice. If it is, the deterministic fallback below still returns a valid sequence.
    private static final int MAX_DRAWS = 200;

    private final RandomSource random;

    public ChallengeSelector(RandomSource random) {
        this.random = random;
    }

    /**
     * @param pool      configured challenge pool (may contain duplicates; they are ignored)
     * @param supported challenge types the running analyzer/detectors can verify
     * @param count     number of challenges to issue (policy.minChallenges)
     * @param previous  the previous attempt's sequence in this session, or null/empty
     * @throws IllegalArgumentException if fewer than {@code count} distinct challenge types are usable
     */
    public List<ChallengeType> select(Collection<ChallengeType> pool, Collection<ChallengeType> supported,
                                      int count, List<ChallengeType> previous) {
        List<ChallengeType> candidates = candidates(pool, supported);
        if (count < 1 || count > candidates.size()) {
            throw new IllegalArgumentException("Need " + count + " challenges but only " + candidates.size()
                    + " usable types: " + candidates);
        }
        List<ChallengeType> fallback = null;
        for (int i = 0; i < MAX_DRAWS; i++) {
            List<ChallengeType> seq = draw(candidates, count);
            if (hasAdjacentOpposites(seq)) continue;
            if (previous != null && seq.equals(previous)) {
                if (fallback == null) fallback = seq;
                continue;
            }
            return seq;
        }
        // Only reached when no alternative to 'previous' exists (e.g. pool of 1): repeating is unavoidable.
        if (fallback != null) return fallback;
        return deterministicValid(candidates, count);
    }

    static List<ChallengeType> candidates(Collection<ChallengeType> pool, Collection<ChallengeType> supported) {
        Set<ChallengeType> set = EnumSet.noneOf(ChallengeType.class);
        for (ChallengeType c : pool) if (c != null && supported.contains(c)) set.add(c);
        return new ArrayList<ChallengeType>(set);
    }

    private List<ChallengeType> draw(List<ChallengeType> candidates, int count) {
        List<ChallengeType> copy = new ArrayList<ChallengeType>(candidates);
        // Fisher-Yates with the injected RandomSource (Collections.shuffle would need a java.util.Random).
        for (int i = copy.size() - 1; i > 0; i--) {
            Collections.swap(copy, i, random.nextInt(i + 1));
        }
        return new ArrayList<ChallengeType>(copy.subList(0, count));
    }

    static boolean hasAdjacentOpposites(List<ChallengeType> seq) {
        for (int i = 1; i < seq.size(); i++) {
            if (opposite(seq.get(i - 1)) == seq.get(i)) return true;
        }
        return false;
    }

    public static ChallengeType opposite(ChallengeType c) {
        switch (c) {
            case TURN_LEFT: return ChallengeType.TURN_RIGHT;
            case TURN_RIGHT: return ChallengeType.TURN_LEFT;
            case LOOK_UP: return ChallengeType.LOOK_DOWN;
            case LOOK_DOWN: return ChallengeType.LOOK_UP;
            default: return null;
        }
    }

    /** Greedy construction used only if random sampling failed; puts non-motion challenges between opposites. */
    private static List<ChallengeType> deterministicValid(List<ChallengeType> candidates, int count) {
        List<ChallengeType> remaining = new ArrayList<ChallengeType>(candidates);
        List<ChallengeType> seq = new ArrayList<ChallengeType>();
        while (seq.size() < count) {
            ChallengeType last = seq.isEmpty() ? null : seq.get(seq.size() - 1);
            ChallengeType pick = null;
            for (ChallengeType c : remaining) {
                if (last == null || opposite(last) != c) { pick = c; break; }
            }
            if (pick == null) pick = remaining.get(0); // unavoidable (e.g. pool is exactly {LEFT, RIGHT})
            remaining.remove(pick);
            seq.add(pick);
        }
        return seq;
    }
}
