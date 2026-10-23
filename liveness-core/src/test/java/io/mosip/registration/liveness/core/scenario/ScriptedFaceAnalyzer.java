package io.mosip.registration.liveness.core.scenario;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.LivenessEngineException;
import io.mosip.registration.liveness.core.testing.Fa;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only FaceAnalyzer (stands in for Part 2): returns the analysis the scenario scripted for each frame sequence
 * number, built around the real Frame object, or throws the scripted error.
 */
public final class ScriptedFaceAnalyzer implements FaceAnalyzer {

    public interface Shape {
        Fa apply(Fa f);
    }

    private final Map<Long, Shape> shapes = new ConcurrentHashMap<Long, Shape>();
    private final Map<Long, ErrorCode> errors = new ConcurrentHashMap<Long, ErrorCode>();
    public final AtomicInteger analysed = new AtomicInteger();
    public volatile boolean closed;

    public void script(long sequence, Shape shape) {
        shapes.put(sequence, shape);
    }

    public void fail(long sequence, ErrorCode code) {
        errors.put(sequence, code);
    }

    @Override
    public FrameAnalysis analyze(Frame frame) throws LivenessEngineException {
        analysed.incrementAndGet();
        ErrorCode e = errors.get(frame.sequence());
        if (e == ErrorCode.ENGINE_ERROR) throw new IllegalStateException("scripted runtime failure");
        if (e != null) throw new LivenessEngineException(e, "scripted");
        Shape s = shapes.get(frame.sequence());
        FrameAnalysis a = (s != null ? s.apply(Fa.at(frame.timestampMs(), frame.sequence()))
                : Fa.at(frame.timestampMs(), frame.sequence())).build();
        // Rebuild around the real frame so bestFrame identity checks work.
        return new FrameAnalysis(frame, a.faceCount, a.faceBox, a.landmarks, a.yawDeg, a.pitchDeg, a.rollDeg,
                a.qualityIssues, a.livenessScore, a.padDetails, a.processingMs);
    }

    @Override
    public String modelVersion() {
        return "scripted-1";
    }

    @Override
    public void close() {
        closed = true;
    }
}
