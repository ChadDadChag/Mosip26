package io.mosip.registration.liveness.analyzer.runtime;

import io.mosip.registration.liveness.analyzer.testing.TestModels;
import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.LivenessEngineException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnnxModelsTest {

    @Test
    void createsOneSessionPerRoleAndClosesThem() throws Exception {
        OnnxModels models = OnnxModels.create(TestModels.load(), 1);
        assertThat(models.env()).isNotNull();
        assertThat(models.session(ModelRole.DETECTOR).getInputNames()).containsExactly("input");
        assertThat(models.session(ModelRole.LANDMARKS).getInputNames()).containsExactly("input_12");
        assertThat(models.session(ModelRole.PAD_V2).getOutputNames()).containsExactly("output");
        assertThat(models.session(ModelRole.PAD_V1SE).getOutputNames()).containsExactly("output");
        models.close();
        assertThatThrownBy(() -> models.session(ModelRole.DETECTOR)).isInstanceOf(IllegalStateException.class);
        models.close(); // idempotent
    }

    @Test
    void rejectsMissingRolesAndBadThreadCounts() throws Exception {
        Map<ModelRole, byte[]> bytes = TestModels.load();
        bytes.remove(ModelRole.PAD_V1SE);
        assertThatThrownBy(() -> OnnxModels.create(bytes, 1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PAD_V1SE");
        assertThatThrownBy(() -> OnnxModels.create(TestModels.load(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OnnxModels.create(null, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnloadableModelIsAnEngineError() throws Exception {
        Map<ModelRole, byte[]> bytes = TestModels.load();
        bytes.put(ModelRole.LANDMARKS, new byte[] {1, 2, 3, 4});
        assertThatThrownBy(() -> OnnxModels.create(bytes, 1))
                .isInstanceOf(LivenessEngineException.class)
                .satisfies(e -> assertThat(((LivenessEngineException) e).code()).isEqualTo(ErrorCode.ENGINE_ERROR))
                .hasMessageContaining("LANDMARKS");
    }

    @Test
    void rolesNameTheBundledFiles() {
        assertThat(ModelRole.DETECTOR.fileName()).isEqualTo("yunet_2026may.onnx");
        assertThat(ModelRole.LANDMARKS.fileName()).isEqualTo("face_mesh_v2.onnx");
        assertThat(ModelRole.PAD_V2.fileName()).isEqualTo("minifasnet_v2_s2.7.onnx");
        assertThat(ModelRole.PAD_V1SE.fileName()).isEqualTo("minifasnet_v1se_s4.0.onnx");
    }
}
