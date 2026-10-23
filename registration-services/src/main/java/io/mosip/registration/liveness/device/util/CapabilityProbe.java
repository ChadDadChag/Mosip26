package io.mosip.registration.liveness.device.util;

import io.mosip.registration.liveness.api.DeviceCapabilities;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Device capability discovery by observation: runs any FrameSource briefly and
 * reports the formats it declares plus the resolutions and frame rate it actually
 * delivers. Needed for SBI devices because MDS device info has no resolution/FPS.
 */
public final class CapabilityProbe {

    private CapabilityProbe() {
    }

    /**
     * @param source       a stopped source; it is started and stopped by this call
     * @param sampleFrames frames to collect (at least 2 for a frame-rate measurement)
     * @param timeoutMs    upper bound on how long to sample
     * @throws IOException if no frame arrived (the device error, if any, is in the message)
     */
    public static DeviceCapabilities probe(FrameSource source, int sampleFrames, long timeoutMs)
            throws IOException, InterruptedException {
        if (source == null || sampleFrames < 1 || timeoutMs <= 0) {
            throw new IllegalArgumentException("source, sampleFrames >= 1 and timeoutMs > 0 are required");
        }
        if (source.isRunning()) {
            throw new IllegalStateException("Probe needs a stopped source");
        }

        List<Frame> frames = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        String[] fatal = new String[1];

        source.start(new FrameSource.Listener() {
            @Override
            public void onFrame(Frame frame) {
                if (frames.size() < sampleFrames) {
                    frames.add(frame);
                }
                if (frames.size() >= sampleFrames) {
                    done.countDown();
                }
            }

            @Override
            public void onError(ErrorCode code, String detail, boolean recoverable) {
                if (!recoverable) {
                    fatal[0] = code + ": " + detail;
                    done.countDown();
                }
            }
        });
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            source.stop();
        }

        List<Frame> sample;
        synchronized (frames) {
            sample = new ArrayList<>(frames);
        }
        if (sample.isEmpty()) {
            throw new IOException("No frames received while probing " + source.info().deviceId
                    + (fatal[0] == null ? " within " + timeoutMs + " ms" : " (" + fatal[0] + ")"));
        }
        return fromSample(source.info().capabilities.streamFormats, sample);
    }

    /** Builds OBSERVED capabilities from delivered frames. FPS uses raw sequence numbers, so stride is accounted for. */
    static DeviceCapabilities fromSample(List<String> streamFormats, List<Frame> sample) {
        List<DeviceCapabilities.Resolution> resolutions = new ArrayList<>();
        for (Frame frame : sample) {
            DeviceCapabilities.Resolution r = new DeviceCapabilities.Resolution(frame.width(), frame.height());
            if (!resolutions.contains(r)) {
                resolutions.add(r);
            }
        }

        List<Integer> frameRates = new ArrayList<>();
        Frame first = sample.get(0);
        Frame last = sample.get(sample.size() - 1);
        long elapsedMs = last.timestampMs() - first.timestampMs();
        long rawFrames = last.sequence() - first.sequence();
        if (elapsedMs > 0 && rawFrames > 0) {
            frameRates.add((int) Math.round(rawFrames * 1000.0 / elapsedMs));
        }
        return new DeviceCapabilities(streamFormats, resolutions, frameRates, DeviceCapabilities.Origin.OBSERVED);
    }
}
