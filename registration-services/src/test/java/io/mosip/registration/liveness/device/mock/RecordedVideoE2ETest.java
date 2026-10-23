package io.mosip.registration.liveness.device.mock;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.DefaultLivenessService;
import io.mosip.registration.liveness.device.LivenessTestSupport;
import io.mosip.registration.liveness.engine.HeuristicFaceAnalyzer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Automated end-to-end liveness tests from recorded videos (bonus task): every clip in
 * {@code src/test/resources/liveness/videos/} (and in the folder named by the system property
 * {@value #VIDEOS_DIR_PROPERTY}, for team recordings kept outside the repo) is played through the real device path
 * ({@link MockFrameSource} video mode), the Part 2 analyzer stand-in ({@link HeuristicFaceAnalyzer}) and the real
 * Part 3 engine, and the result is checked against the expectation in the file name:
 * <pre>
 *   &lt;name&gt;__expect-PASSED.mjpeg
 *   &lt;name&gt;__expect-FAILED.mjpeg
 *   &lt;name&gt;__expect-FAILED-&lt;ErrorCode&gt;.mjpeg   e.g. printed-photo__expect-FAILED-PAD_FAILURE.mjpeg
 * </pre>
 * One attempt per clip (maxRetries=0): the code is compared with the final code, or with the audited last cause
 * when the session ended in MAX_RETRIES_EXCEEDED. Record clips with {@code scripts/record-liveness-clip.sh}.
 *
 * <p>Recorded clips can only exercise passive decisions and attacks: challenge prompts are random, so a recording
 * cannot answer them (that is the point). Challenge flows are covered by the engine scenario tests and the harness.
 */
@RunWith(Parameterized.class)
public class RecordedVideoE2ETest {

    public static final String VIDEOS_DIR_PROPERTY = "liveness.videos.dir";
    static final long TIMEOUT_SEC = 60;

    private final File clip;
    private final String expectedDecision;
    private final String expectedCode;

    public RecordedVideoE2ETest(String name, File clip) {
        this.clip = clip;
        String expect = name.substring(name.indexOf("__expect-") + "__expect-".length()).replace(".mjpeg", "");
        int dash = expect.indexOf('-');
        this.expectedDecision = (dash < 0 ? expect : expect.substring(0, dash)).toUpperCase(Locale.ROOT);
        this.expectedCode = dash < 0 ? null : expect.substring(dash + 1).toUpperCase(Locale.ROOT);
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> clips() throws Exception {
        List<File> dirs = new ArrayList<>();
        URL bundled = RecordedVideoE2ETest.class.getResource("/liveness/videos");
        if (bundled != null) dirs.add(new File(bundled.toURI()));
        String extra = System.getProperty(VIDEOS_DIR_PROPERTY);
        if (extra != null && !extra.trim().isEmpty()) dirs.add(new File(extra.trim()));
        List<Object[]> out = new ArrayList<>();
        for (File dir : dirs) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            Arrays.sort(files);
            for (File f : files) {
                if (f.getName().endsWith(".mjpeg") && f.getName().contains("__expect-")) {
                    out.add(new Object[] {f.getName(), f});
                }
            }
        }
        return out;
    }

    @Test
    public void clipReachesTheExpectedDecision() throws Exception {
        LivenessTestSupport.Audit audit = new LivenessTestSupport.Audit();
        Map<String, String> config = LivenessTestSupport.config(LivenessPolicy.PREFIX + "default.maxRetries", "0");
        DefaultLivenessService svc = LivenessTestSupport.service(config, HeuristicFaceAnalyzer::new, audit);
        LivenessTestSupport.Recorder rec = new LivenessTestSupport.Recorder();
        svc.newSession(WorkflowType.RESIDENT).start(MockFrameSource.ofVideoFile(clip.toPath(), 15.0, true), rec);
        LivenessResult r = rec.await(TIMEOUT_SEC);

        assertEquals(clip.getName() + " events " + rec.types(), expectedDecision, r.decision.name());
        if (expectedCode != null) {
            String actual = r.failureCode == null ? null : r.failureCode.name();
            if (r.failureCode == ErrorCode.MAX_RETRIES_EXCEEDED && !audit.finals.isEmpty()) {
                actual = audit.finals.get(audit.finals.size() - 1).get("lastCause");
            }
            assertEquals(clip.getName(), expectedCode, actual);
        }
        assertEquals("exactly one result", 1, rec.completions);
        for (Map<String, String> d : audit.all) {
            for (String v : d.values()) assertTrue("audit stays small and image-free", v.length() < 400);
        }
    }
}
