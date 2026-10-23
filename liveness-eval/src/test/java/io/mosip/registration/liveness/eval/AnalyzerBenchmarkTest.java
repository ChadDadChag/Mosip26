package io.mosip.registration.liveness.eval;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AnalyzerBenchmarkTest {

    private static double[] oneToHundred() {
        double[] v = new double[100];
        for (int i = 0; i < 100; i++) v[99 - i] = i + 1; // unsorted on purpose
        return v;
    }

    @Test
    void nearestRankPercentilesAndMean() {
        AnalyzerBenchmark.Summary s = new AnalyzerBenchmark.Summary(oneToHundred());
        assertThat(s.p50).isEqualTo(50);
        assertThat(s.p95).isEqualTo(95);
        assertThat(s.p99).isEqualTo(99);
        assertThat(s.mean).isCloseTo(50.5, within(1e-12));
        assertThat(s.n).isEqualTo(100);
        assertThat(AnalyzerBenchmark.percentile(new double[] {7}, 99)).isEqualTo(7);
        assertThat(AnalyzerBenchmark.percentile(new double[0], 50)).isNaN();
        assertThat(new AnalyzerBenchmark.Summary(new double[0]).mean).isNaN();
    }

    @Test
    void reportsListEveryStageAndColdStart() {
        AnalyzerBenchmark.Summary[] stages = new AnalyzerBenchmark.Summary[AnalyzerBenchmark.STAGES.length];
        for (int i = 0; i < stages.length; i++) stages[i] = new AnalyzerBenchmark.Summary(oneToHundred());
        AnalyzerBenchmark.Result r = new AnalyzerBenchmark.Result(2, 1.5, 250.0, 40.0, stages);
        String csv = AnalyzerBenchmark.csv(Collections.singletonList(r));
        assertThat(csv).startsWith("threads,metric,p50_ms,p95_ms,p99_ms,mean_ms,n\n");
        assertThat(csv).contains("2,cold_preload,250.000", "2,detect,50.000,95.000,99.000,50.500,100", "2,total,");
        String md = AnalyzerBenchmark.markdown(Collections.singletonList(r), "test input", 20, 300, "Test CPU",
                new io.mosip.registration.liveness.api.Frame(new byte[12], 2, 2, 1, 0, "t"));
        assertThat(md).contains("| CPU | Test CPU |", "| 2 | 1.5 | 250.0 | 40.0 |", "| detect |", "| total |", "2 threads");
        assertThat(AnalyzerBenchmark.cpuName()).isNotBlank();
        assertThat(AnalyzerBenchmark.ortVersion()).startsWith("onnxruntime");
    }
}
