package io.mosip.registration.liveness.analyzer;

/**
 * Per-analyzer statistics: the last frame's stage times in nanoseconds (0 when a stage did not run) and counters
 * since creation. Written only by the owning {@link OnnxFaceAnalyzer}; read by the benchmark and the demo.
 */
public final class AnalyzerStats {

    long detectNs, meshNs, poseNs, qualityNs, padNs, cuesNs, totalNs;
    long frames, framesNoFace, framesOneFace, framesMultiFace, framesPoseGated;
    long framesTracked, trackingFallbacks;

    AnalyzerStats() { }

    void beginFrame() {
        detectNs = meshNs = poseNs = qualityNs = padNs = cuesNs = totalNs = 0;
        frames++;
    }

    public long lastDetectNs() { return detectNs; }
    public long lastMeshNs() { return meshNs; }
    public long lastPoseNs() { return poseNs; }
    public long lastQualityNs() { return qualityNs; }
    public long lastPadNs() { return padNs; }
    public long lastCuesNs() { return cuesNs; }
    public long lastTotalNs() { return totalNs; }

    public long frames() { return frames; }
    public long framesNoFace() { return framesNoFace; }
    public long framesOneFace() { return framesOneFace; }
    public long framesMultiFace() { return framesMultiFace; }
    public long framesPoseGated() { return framesPoseGated; }
    /** Frames whose mesh ROI came from the previous frame's landmarks (F7). */
    public long framesTracked() { return framesTracked; }
    /** Tracked frames whose mesh presence fell below the threshold and were re-run on the YuNet ROI. */
    public long trackingFallbacks() { return trackingFallbacks; }
}
