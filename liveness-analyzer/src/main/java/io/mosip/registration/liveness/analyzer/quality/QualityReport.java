package io.mosip.registration.liveness.analyzer.quality;

import io.mosip.registration.liveness.api.FrameAnalysis.QualityIssue;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Quality metrics of the primary face and the issues they raise ({@code reference_pipeline.quality}). */
public final class QualityReport {

    public final double lumaMean;
    public final double lapVar;
    public final double areaFrac;
    public final double faceWidthPx;
    public final double centreDx, centreDy;
    public final boolean cutOff;
    private final EnumSet<QualityIssue> issues;

    QualityReport(double lumaMean, double lapVar, double areaFrac, double faceWidthPx, double centreDx,
                  double centreDy, boolean cutOff, EnumSet<QualityIssue> issues) {
        this.lumaMean = lumaMean;
        this.lapVar = lapVar;
        this.areaFrac = areaFrac;
        this.faceWidthPx = faceWidthPx;
        this.centreDx = centreDx;
        this.centreDy = centreDy;
        this.cutOff = cutOff;
        this.issues = issues;
    }

    /** Read-only view of the issues. */
    public Set<QualityIssue> issues() {
        return Collections.unmodifiableSet(issues);
    }

    /** A fresh mutable copy, for {@code FrameAnalysis}. */
    public EnumSet<QualityIssue> issuesCopy() {
        return EnumSet.copyOf(issues);
    }
}
