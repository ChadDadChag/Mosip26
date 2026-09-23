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
}
