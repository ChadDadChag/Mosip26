package io.mosip.registration.liveness.device.demo;

import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.DefaultLivenessService;
import io.mosip.registration.liveness.device.LivenessTestSupport;
import io.mosip.registration.liveness.device.LivenessTestSupport.Scene;
import io.mosip.registration.liveness.device.mock.MockFrameSource;
import io.mosip.registration.liveness.device.util.PreviewFrameSource;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HarnessModelTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void showsTheProblemStatementMessagesForEachEvent() {
        HarnessModel m = new HarnessModel();
        m.onEvent(LivenessEvent.of(LivenessEvent.Type.CHECKING_PASSIVE, 1));
        assertEquals("Checking face liveness...", m.snapshot().status);
        m.onEvent(LivenessEvent.prompt(ChallengeType.TURN_LEFT, 1, 2, 6000, 1));
        assertEquals("Please turn your head to the left", m.snapshot().prompt);
        assertEquals("Challenge 1 of 2", m.snapshot().status);
        m.onEvent(LivenessEvent.progress(ChallengeType.TURN_LEFT, 1, 2, 0.5f, 1));
        assertEquals("Please continue", m.snapshot().guidance);
        m.onEvent(LivenessEvent.of(LivenessEvent.Type.ACTION_DETECTED, 1));
        assertEquals("Action detected", m.snapshot().guidance);
        m.onEvent(LivenessEvent.of(LivenessEvent.Type.HOLD_STILL, 1));
        assertEquals("Hold still", m.snapshot().status);
        m.onEvent(LivenessEvent.of(LivenessEvent.Type.PASSED, 1));
        assertEquals("Good, face captured successfully", m.snapshot().status);
        m.onEvent(LivenessEvent.failure(LivenessEvent.Type.ATTEMPT_FAILED, ErrorCode.PAD_FAILURE, 1, 2, true));
        assertTrue("PAD shows only the generic text",
                m.snapshot().status.startsWith("Face verification could not be completed. Please try again."));
        m.onEvent(LivenessEvent.guidance(LivenessEvent.Guidance.IMPROVE_LIGHTING, 1));
        assertEquals("Improve lighting", m.snapshot().guidance);
        assertTrue(m.snapshot().log.size() <= HarnessModel.LOG_LINES);
    }

    @Test
    public void headlessHarnessRunsTheRealEngineAndPressesRetry() throws Exception {
        File live = tmp.newFile("live.mjpeg");
        Files.write(live.toPath(), LivenessTestSupport.recording(Scene.LIVE, 30));
        final HarnessModel model = new HarnessModel();
        final AtomicInteger changes = new AtomicInteger();
        model.addChangeListener(changes::incrementAndGet);
        DefaultLivenessService svc = LivenessTestSupport.service(LivenessTestSupport.config(),
                () -> model.observing(new LivenessTestSupport.ColourAnalyzer()), null);
        PreviewFrameSource source = new PreviewFrameSource(MockFrameSource.ofVideoFile(live.toPath(), 30, true));
        final AtomicInteger previewFrames = new AtomicInteger();
        source.addPreviewListener(f -> previewFrames.incrementAndGet());

        assertEquals(0, LivenessHarness.runHeadless(svc, source, model, WorkflowType.SUPERVISOR));
        HarnessModel.Snapshot s = model.snapshot();
        assertTrue(s.result, s.result.startsWith("PASSED (passive)"));
        assertTrue("the overlay saw analyses", s.framesAnalysed >= 10);
        assertEquals(0.95f, s.lastScore, 1e-6);
        assertTrue(previewFrames.get() > s.framesAnalysed);
        assertTrue(changes.get() > 0);

        // An attack is retried automatically until the final result.
        File attack = tmp.newFile("attack.mjpeg");
        Files.write(attack.toPath(), LivenessTestSupport.recording(Scene.ATTACK, 30));
        final HarnessModel m2 = new HarnessModel();
        DefaultLivenessService svc2 = LivenessTestSupport.service(
                LivenessTestSupport.config(LivenessPolicy.PREFIX + "default.maxRetries", "1"),
                () -> m2.observing(new LivenessTestSupport.ColourAnalyzer()), null);
        assertEquals(1, LivenessHarness.runHeadless(svc2,
                MockFrameSource.ofVideoFile(attack.toPath(), 30, true), m2, WorkflowType.RESIDENT));
        assertTrue(m2.snapshot().result, m2.snapshot().result.startsWith("FAILED MAX_RETRIES_EXCEEDED, attempts 2"));
    }

    @Test
    public void optionsParsing() {
        LivenessHarness.Options o = LivenessHarness.Options.parse(
                new String[] {"video", "clip.mjpeg", "12", "--workflow", "operator", "--diagnostic", "--headless"});
        assertEquals("video", o.mode);
        assertEquals("clip.mjpeg", o.path);
        assertEquals(12.0, o.fps, 0);
        assertEquals(WorkflowType.OPERATOR, o.workflow);
        assertTrue(o.diagnostic && o.headless);
        assertNull(LivenessHarness.Options.parse(new String[] {"video"}));
        assertNull(LivenessHarness.Options.parse(new String[] {"hologram"}));
        assertEquals("webcam", LivenessHarness.Options.parse(new String[] {"webcam"}).mode);
    }
}
