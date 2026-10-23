package io.mosip.registration.liveness.core.challenge;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.core.util.SeededRandomSource;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.mosip.registration.liveness.api.ChallengeType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChallengeSelectorTest {

    private static final Set<ChallengeType> ALL = EnumSet.allOf(ChallengeType.class);
    private static final List<ChallengeType> DEFAULT_POOL = Arrays.asList(BLINK, SMILE, TURN_LEFT, TURN_RIGHT);

    @Test
    void sequencesAreDistinctFromPoolAndNeverPairOppositesAdjacently() {
        ChallengeSelector s = new ChallengeSelector(new SeededRandomSource(42));
        for (int i = 0; i < 2000; i++) {
            List<ChallengeType> seq = s.select(ALL, ALL, 4, null);
            assertThat(new HashSet<ChallengeType>(seq)).hasSize(4);
            assertThat(ChallengeSelector.hasAdjacentOpposites(seq)).isFalse();
        }
    }

    @Test
    void retrySequenceDiffersFromPrevious() {
        ChallengeSelector s = new ChallengeSelector(new SeededRandomSource(7));
        List<ChallengeType> previous = s.select(DEFAULT_POOL, ALL, 2, null);
        for (int i = 0; i < 500; i++) {
            List<ChallengeType> next = s.select(DEFAULT_POOL, ALL, 2, previous);
            assertThat(next).isNotEqualTo(previous);
            previous = next;
        }
    }

    @Test
    void onlyUsesSupportedTypesAndIgnoresDuplicates() {
        ChallengeSelector s = new ChallengeSelector(new SeededRandomSource(1));
        List<ChallengeType> pool = Arrays.asList(BLINK, BLINK, SMILE, LOOK_UP);
        for (int i = 0; i < 200; i++) {
            assertThat(s.select(pool, EnumSet.of(BLINK, SMILE), 2, null)).containsExactlyInAnyOrder(BLINK, SMILE);
        }
    }

    @Test
    void rejectsCountLargerThanUsablePool() {
        ChallengeSelector s = new ChallengeSelector(new SeededRandomSource(1));
        assertThatThrownBy(() -> s.select(Arrays.asList(BLINK, BLINK), ALL, 2, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void singleTypePoolRepeatsOnlyBecauseThereIsNoAlternative() {
        ChallengeSelector s = new ChallengeSelector(new SeededRandomSource(1));
        List<ChallengeType> only = Arrays.asList(BLINK);
        assertThat(s.select(only, ALL, 1, Arrays.asList(BLINK))).containsExactly(BLINK);
    }

    @Test
    void oppositesOnlyPoolStillReturnsASequence() {
        ChallengeSelector s = new ChallengeSelector(new SeededRandomSource(1));
        assertThat(s.select(Arrays.asList(TURN_LEFT, TURN_RIGHT), ALL, 2, null))
                .containsExactlyInAnyOrder(TURN_LEFT, TURN_RIGHT);
    }

    /** 10,000 draws, chained like real retries: every valid sequence appears and none dominates. */
    @Test
    void distributionIsUniformOverValidSequences() {
        ChallengeSelector s = new ChallengeSelector(new SeededRandomSource(2026));
        java.util.Map<List<ChallengeType>, Integer> counts = new java.util.HashMap<List<ChallengeType>, Integer>();
        java.util.Map<ChallengeType, Integer> perType = new java.util.EnumMap<ChallengeType, Integer>(ChallengeType.class);
        List<ChallengeType> previous = null;
        for (int i = 0; i < 10000; i++) {
            List<ChallengeType> seq = s.select(DEFAULT_POOL, ALL, 2, previous);
            Integer c = counts.get(seq);
            counts.put(seq, c == null ? 1 : c + 1);
            for (ChallengeType t : seq) {
                Integer p = perType.get(t);
                perType.put(t, p == null ? 1 : p + 1);
            }
            previous = seq;
        }
        // 4 types, ordered pairs = 12, minus TURN_LEFT/TURN_RIGHT in either order = 10 valid sequences.
        assertThat(counts).hasSize(10);
        for (int c : counts.values()) assertThat(c).isBetween(850, 1150); // expected 1000
        assertThat(perType.keySet()).containsExactlyInAnyOrder(BLINK, SMILE, TURN_LEFT, TURN_RIGHT);
        // Sequences are uniform, types are not: both turns can never be drawn together (they are opposites), so
        // BLINK/SMILE are in 6 of the 10 valid sequences and each turn in 4 -> expected 6000 vs 4000 appearances.
        assertThat(perType.get(BLINK)).isBetween(5500, 6500);
        assertThat(perType.get(SMILE)).isBetween(5500, 6500);
        assertThat(perType.get(TURN_LEFT)).isBetween(3500, 4500);
        assertThat(perType.get(TURN_RIGHT)).isBetween(3500, 4500);
    }

    @Test
    void singleChallengeDrawIsUniformOverMotionChallengesAndNeverRepeatsBackToBack() {
        ChallengeSelector s = new ChallengeSelector(new SeededRandomSource(99));
        java.util.Map<ChallengeType, Integer> counts = new java.util.EnumMap<ChallengeType, Integer>(ChallengeType.class);
        List<ChallengeType> previous = null;
        for (int i = 0; i < 10000; i++) {
            List<ChallengeType> seq = s.select(DEFAULT_POOL, ALL, 1, previous);
            assertThat(seq).isNotEqualTo(previous);
            Integer c = counts.get(seq.get(0));
            counts.put(seq.get(0), c == null ? 1 : c + 1);
            previous = seq;
        }
        // BLINK is never the only challenge (natural blinking in a replay would satisfy it): 3 types left.
        assertThat(counts).doesNotContainKey(BLINK);
        assertThat(counts.keySet()).containsExactlyInAnyOrder(SMILE, TURN_LEFT, TURN_RIGHT);
        for (int c : counts.values()) assertThat(c).isBetween(3000, 3667); // expected 3333
    }

    @Test
    void blinkAloneOnlyWhenItIsTheWholePool() {
        ChallengeSelector s = new ChallengeSelector(new SeededRandomSource(4));
        for (int i = 0; i < 500; i++) {
            assertThat(s.select(Arrays.asList(BLINK, SMILE), ALL, 1, null)).containsExactly(SMILE);
            assertThat(s.select(Arrays.asList(BLINK), ALL, 1, null)).containsExactly(BLINK);
        }
        // With two challenges BLINK is still used, paired with a motion.
        boolean sawBlink = false;
        for (int i = 0; i < 500; i++) sawBlink |= s.select(DEFAULT_POOL, ALL, 2, null).contains(BLINK);
        assertThat(sawBlink).isTrue();
    }
}
