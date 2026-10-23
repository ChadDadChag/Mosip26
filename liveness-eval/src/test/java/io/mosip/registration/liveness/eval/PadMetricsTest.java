package io.mosip.registration.liveness.eval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PadMetricsTest {

    /** bona fide 0.95 0.90 0.80 0.25 + one non-response; screen_photo 0.10 0.20 0.90; screen_replay 0.05 0.05 + one non-response. */
    static List<PadMetrics.Presentation> sample() {
        List<PadMetrics.Presentation> ps = new ArrayList<PadMetrics.Presentation>();
        for (float s : new float[] {0.95f, 0.90f, 0.80f, 0.25f, Float.NaN}) ps.add(new PadMetrics.Presentation("bona_fide", s));
        for (float s : new float[] {0.10f, 0.20f, 0.90f}) ps.add(new PadMetrics.Presentation("screen_photo", s));
        for (float s : new float[] {0.05f, 0.05f, Float.NaN}) ps.add(new PadMetrics.Presentation("screen_replay", s));
        return ps;
    }

    @Test
    void apcerPerSpeciesBpcerAndAcerAtAThreshold() {
        List<PadMetrics.Presentation> ps = sample();
        assertThat(PadMetrics.species(ps)).containsExactly("screen_photo", "screen_replay");
        PadMetrics.Rate photo = PadMetrics.apcer(ps, "screen_photo", 0.85f);
        assertThat(photo.x).isEqualTo(1);
        assertThat(photo.n).isEqualTo(3);
        PadMetrics.Rate replay = PadMetrics.apcer(ps, "screen_replay", 0.85f);
        assertThat(replay.x).isZero();
        assertThat(replay.n).isEqualTo(2); // the non-response is not in the denominator
        assertThat(PadMetrics.maxApcer(ps, 0.85f)).isCloseTo(1 / 3.0, within(1e-9));
        PadMetrics.Rate bpcer = PadMetrics.bpcer(ps, 0.85f);
        assertThat(bpcer.x).isEqualTo(2);
        assertThat(bpcer.n).isEqualTo(4);
        assertThat(PadMetrics.acer(ps, 0.85f)).isCloseTo(5 / 12.0, within(1e-9));
        // A score exactly at the threshold is classified bona fide, as the engine passes median >= passiveThreshold.
        assertThat(PadMetrics.apcer(ps, "screen_photo", 0.90f).x).isEqualTo(1);
        assertThat(PadMetrics.bpcer(ps, 0.25f).x).isZero();
    }

    @Test
    void nonResponsesAreCountedSeparately() {
        List<PadMetrics.Presentation> ps = sample();
        PadMetrics.Rate apnrr = PadMetrics.nonResponse(ps, true), bpnrr = PadMetrics.nonResponse(ps, false);
        assertThat(apnrr.x).isEqualTo(1);
        assertThat(apnrr.n).isEqualTo(6);
        assertThat(bpnrr.x).isEqualTo(1);
        assertThat(bpnrr.n).isEqualTo(5);
        List<PadMetrics.Presentation> onlyNr = new ArrayList<PadMetrics.Presentation>();
        onlyNr.add(new PadMetrics.Presentation("screen_photo", Float.NaN));
        assertThat(PadMetrics.maxApcer(onlyNr, 0.5f)).isNaN(); // no responding attack: no APCER, not 0
    }

    @Test
    void equalErrorRateAndBpcerAtAnApcerTarget() {
        List<PadMetrics.Presentation> ps = sample();
        double[] eer = PadMetrics.eer(ps);
        // Closest point: max APCER 1/3 (0.90 photo) and BPCER 1/4 (0.25 bona fide), just above 0.25.
        assertThat(eer[0]).isCloseTo((1 / 3.0 + 1 / 4.0) / 2, within(1e-9));
        assertThat(eer[1]).isGreaterThan(0.25).isLessThan(0.2501);
        double[] b5 = PadMetrics.bpcerAtApcer(ps, 0.05);
        assertThat(b5[0]).isCloseTo(0.75, within(1e-9)); // only above 0.90 is APCER 0; then 3 of 4 bona fide fail
        assertThat(b5[1]).isGreaterThan(0.90);
    }

    @Test
    void threeZonesUseBothThresholds() {
        Map<String, int[]> z = PadMetrics.zones(sample(), 0.85f, 0.30f);
        assertThat(z.keySet()).containsExactly("bona_fide", "screen_photo", "screen_replay");
        assertThat(z.get("bona_fide")).containsExactly(2, 1, 1, 1, 5);   // pass, challenge, reject, no response, total
        assertThat(z.get("screen_photo")).containsExactly(1, 0, 2, 0, 3);
        assertThat(z.get("screen_replay")).containsExactly(0, 0, 2, 1, 3);
        assertThat(PadMetrics.zone(0.30f, 0.85f, 0.30f)).isEqualTo(PadMetrics.Zone.CHALLENGE);
        assertThat(PadMetrics.zone(0.85f, 0.85f, 0.30f)).isEqualTo(PadMetrics.Zone.PASSIVE_PASS);
    }

    @Test
    void recommendationFollowsTheGridRule() {
        PadMetrics.Recommendation r = PadMetrics.recommend(sample(), PadMetrics.grid());
        assertThat(r.passiveThreshold).isEqualTo(0.97f);  // first zero APCER at 0.95, + 0.02
        assertThat(r.padRejectThreshold).isEqualTo(0.20f); // lowest bona fide 0.25 -> grid 0.25, - 0.05
        assertThat(r.notes).isEmpty();

        List<PadMetrics.Presentation> hard = new ArrayList<PadMetrics.Presentation>();
        hard.add(new PadMetrics.Presentation("bona_fide", 0.99f));
        hard.add(new PadMetrics.Presentation("screen_photo_close", 0.9995f));
        PadMetrics.Recommendation h = PadMetrics.recommend(hard, PadMetrics.grid());
        assertThat(h.passiveThreshold).isNaN();
        assertThat(h.notes).anyMatch(n -> n.contains("no grid value"));
        assertThat(PadMetrics.grid()).hasSize(21).startsWith(0.05f).endsWith(0.99f, 0.999f);
    }

    @Test
    void clopperPearsonUpperBound() {
        assertThat(PadMetrics.clopperPearsonUpper(0, 6, 0.95)).isCloseTo(1 - Math.pow(0.05, 1 / 6.0), within(1e-6));
        assertThat(PadMetrics.clopperPearsonUpper(0, 10, 0.95)).isCloseTo(0.2589, within(1e-4));
        assertThat(PadMetrics.clopperPearsonUpper(1, 10, 0.95)).isCloseTo(0.3942, within(1e-4));
        assertThat(PadMetrics.clopperPearsonUpper(5, 5, 0.95)).isEqualTo(1.0);
        assertThat(PadMetrics.clopperPearsonUpper(0, 0, 0.95)).isNaN();
        assertThat(new PadMetrics.Rate(0, 6).format()).isEqualTo("0/6 (0.0%) [<=39.3%]");
        assertThat(new PadMetrics.Rate(2, 4).format()).isEqualTo("2/4 (50.0%)");
        assertThat(new PadMetrics.Rate(0, 0).format()).isEqualTo("n/a");
    }
}
