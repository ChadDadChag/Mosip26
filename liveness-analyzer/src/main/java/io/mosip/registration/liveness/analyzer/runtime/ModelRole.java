package io.mosip.registration.liveness.analyzer.runtime;

/** The four models of the analyzer and their file names in the model bundle ({@code models/}). */
public enum ModelRole {
    DETECTOR("yunet_2026may.onnx"),
    LANDMARKS("face_mesh_v2.onnx"),
    PAD_V2("minifasnet_v2_s2.7.onnx"),
    PAD_V1SE("minifasnet_v1se_s4.0.onnx");

    private final String fileName;

    ModelRole(String fileName) {
        this.fileName = fileName;
    }

    public String fileName() {
        return fileName;
    }
}
