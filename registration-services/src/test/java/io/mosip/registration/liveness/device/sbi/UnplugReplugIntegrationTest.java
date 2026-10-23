package io.mosip.registration.liveness.device.sbi;

import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.exception.RegistrationExceptionConstants;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.LivenessEvent;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.WorkflowType;
import io.mosip.registration.liveness.core.DefaultLivenessService;
import io.mosip.registration.liveness.device.LivenessTestSupport;
import io.mosip.registration.liveness.device.LivenessTestSupport.Scene;
import io.mosip.registration.mdm.dto.MdmBioDevice;
import io.mosip.registration.service.bio.BioService;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Part 1 "done when", end to end: unplugging the camera mid-capture shows a clean DEVICE_ERROR with Retry, and
 * replugging it (then Retry) works. Real SbiStreamFrameSource (reconnect + backoff) + real Part 3 engine; only the
 * MDS and the face analyzer are test doubles.
 */
public class UnplugReplugIntegrationTest {

    @Test
    public void unplugMidCaptureShowsDeviceErrorWithRetryAndReplugPasses() throws Exception {
        final AtomicBoolean plugged = new AtomicBoolean(true);
        final AtomicInteger connections = new AtomicInteger();
        final byte[] live = LivenessTestSupport.part(LivenessTestSupport.jpeg(Scene.LIVE));
        BioService bio = FakeBioService.returning(() -> {
            int n = connections.incrementAndGet();
            if (!plugged.get()) {
                throw new RegBaseCheckedException(RegistrationExceptionConstants.MDS_BIODEVICE_NOT_FOUND.getErrorCode(),
                        "device not found");
            }
            if (n == 1) {
                plugged.set(false); // the cable is pulled after a few frames of the first connection
                return LivenessTestSupport.liveStream(live, 33, 4);
            }
            return LivenessTestSupport.liveStream(live, 33, -1);
        });
        MdmBioDevice device = new MdmBioDevice();
        device.setDeviceId("face-1");
        SbiStreamFrameSource source = new SbiStreamFrameSource(bio, device, "FACE_FULLFACE", 5000L, 1, 2, 50L);

        LivenessTestSupport.Audit audit = new LivenessTestSupport.Audit();
        DefaultLivenessService svc = LivenessTestSupport.colourService(LivenessTestSupport.config(), audit);
        LivenessSession session = svc.newSession(WorkflowType.RESIDENT);
        final LivenessTestSupport.Recorder rec = new LivenessTestSupport.Recorder();
        session.start(source, rec);

        // Wait for the device error the UI would show.
        long deadline = System.currentTimeMillis() + 10000;
        while (rec.first(LivenessEvent.Type.DEVICE_ERROR) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        LivenessEvent deviceError = rec.first(LivenessEvent.Type.DEVICE_ERROR);
        assertNotNull("unplug must produce DEVICE_ERROR; events " + rec.types(), deviceError);
        assertTrue("the UI must offer Retry", deviceError.recoverable);
        assertEquals(ErrorCode.UserMessage.DEVICE_PROBLEM, deviceError.error.userMessage());
        Thread.sleep(500); // the source exhausts its reconnects (device not found) meanwhile
        assertNull("the session must wait for the user, not fail", rec.result);

        plugged.set(true); // camera plugged back in
        session.retry();   // user presses Retry

        LivenessResult r = rec.await(15);
        assertTrue("replug + Retry must pass; events " + rec.types(), r.passed());
        assertEquals(1, rec.completions);
        assertEquals("no liveness attempt is used up by the device problem", 1, r.attempts);
        assertFalse(rec.types().contains(LivenessEvent.Type.ATTEMPT_FAILED));
        assertTrue("Retry restarted the device stream", connections.get() >= 3);
    }

    @Test
    public void cameraThatNeverComesBackEndsCleanlyAfterTheDeviceRetryLimit() throws Exception {
        final AtomicInteger connections = new AtomicInteger();
        final byte[] live = LivenessTestSupport.part(LivenessTestSupport.jpeg(Scene.LIVE));
        BioService bio = FakeBioService.returning(() -> {
            if (connections.incrementAndGet() == 1) return LivenessTestSupport.liveStream(live, 33, 4);
            throw new RegBaseCheckedException(RegistrationExceptionConstants.MDS_BIODEVICE_NOT_FOUND.getErrorCode(),
                    "device not found");
        });
        MdmBioDevice device = new MdmBioDevice();
        device.setDeviceId("face-1");
        SbiStreamFrameSource source = new SbiStreamFrameSource(bio, device, "FACE_FULLFACE", 5000L, 1, 1, 30L);
        DefaultLivenessService svc = LivenessTestSupport.colourService(LivenessTestSupport.config(),
                new LivenessTestSupport.Audit());
        final LivenessSession session = svc.newSession(WorkflowType.OPERATOR);
        LivenessTestSupport.Recorder rec = new LivenessTestSupport.Recorder() {
            @Override
            public void onEvent(LivenessEvent e) {
                super.onEvent(e);
                if (e.type == LivenessEvent.Type.DEVICE_ERROR && e.recoverable) session.retry(); // impatient user
            }
        };
        session.start(source, rec);
        LivenessResult r = rec.await(30);
        assertEquals(LivenessResult.Decision.FAILED, r.decision);
        assertEquals(ErrorCode.UserMessage.DEVICE_PROBLEM, r.failureCode.userMessage());
        LivenessEvent last = null;
        synchronized (rec.events) {
            for (LivenessEvent e : rec.events) if (e.type == LivenessEvent.Type.DEVICE_ERROR) last = e;
        }
        assertNotNull(last);
        assertFalse("the final device error carries no Retry", last.recoverable);
    }
}
