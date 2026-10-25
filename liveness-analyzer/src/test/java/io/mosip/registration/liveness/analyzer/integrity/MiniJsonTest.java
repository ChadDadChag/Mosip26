package io.mosip.registration.liveness.analyzer.integrity;

import io.mosip.registration.liveness.analyzer.integrity.MiniJson.JsonException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MiniJsonTest {

    @Test
    void parsesAllValueTypesAndKeepsKeyOrder() throws Exception {
        Map<String, Object> o = MiniJson.parseObject(
                " {\"z\": 1, \"a\": -2.5e2, \"s\": \"x\", \"t\": true, \"f\": false, \"n\": null, \"arr\": [1, [2], {}]} ");
        assertThat(o.keySet()).containsExactly("z", "a", "s", "t", "f", "n", "arr");
        assertThat(MiniJson.getDouble(o, "z")).isEqualTo(1.0);
        assertThat(MiniJson.getInt(o, "z")).isEqualTo(1);
        assertThat(MiniJson.getLong(o, "z")).isEqualTo(1L);
        assertThat(MiniJson.getDouble(o, "a")).isEqualTo(-250.0);
        assertThat(MiniJson.getString(o, "s")).isEqualTo("x");
        assertThat(MiniJson.getBoolean(o, "t")).isTrue();
        assertThat(MiniJson.getBoolean(o, "f")).isFalse();
        assertThat(MiniJson.isNull(o, "n")).isTrue();
        List<Object> arr = MiniJson.getArray(o, "arr");
        assertThat(arr).hasSize(3);
        assertThat(arr.get(1)).isEqualTo(Arrays.<Object>asList(2.0));
        assertThat((Map<?, ?>) arr.get(2)).isEmpty();
    }

    @Test
    void decodesAllEscapes() throws Exception {
        Object v = MiniJson.parse("\"q\\\" b\\\\ s\\/ \\b\\f\\n\\r\\t \\u00e9\\u20AC\"");
        assertThat(v).isEqualTo("q\" b\\ s/ \b\f\n\r\t \u00e9\u20ac");
    }

    @Test
    void parsesTopLevelScalarsAndEmptyContainers() throws Exception {
        assertThat(MiniJson.parse("0")).isEqualTo(0.0);
        assertThat(MiniJson.parse("-0.5")).isEqualTo(-0.5);
        assertThat(MiniJson.parse("1E+3")).isEqualTo(1000.0);
        assertThat(MiniJson.parse("[]")).isEqualTo(Arrays.asList());
        assertThat(MiniJson.parse("null")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", " ", "{", "[1,]", "{\"a\":1,}", "{\"a\" 1}", "{a:1}", "[1 2]", "\"abc", "\"a\\x\"", "\"\\u12G4\"",
        "\"\\u12\"", "\"tab\there\"", "01", "-", "+1", ".5", "1.", "1e", "1e+", "NaN", "Infinity", "-Infinity",
        "1e400", "tru", "nul", "{\"a\":1}x", "[1] [2]", "{\"a\":1,\"a\":2}", "'single'", "[1,\n", "\"\\"
    })
    void rejectsInvalidJson(String json) {
        assertThatThrownBy(() -> MiniJson.parse(json)).isInstanceOf(JsonException.class);
    }

    @Test
    void rejectsNullInputAndNonObjectTopLevel() {
        assertThatThrownBy(() -> MiniJson.parse(null)).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> MiniJson.parseObject("[1]")).isInstanceOf(JsonException.class);
    }

    @Test
    void limitsNestingDepth() throws Exception {
        StringBuilder ok = new StringBuilder(), deep = new StringBuilder();
        for (int i = 0; i < MiniJson.MAX_DEPTH; i++) ok.append('[');
        for (int i = 0; i < MiniJson.MAX_DEPTH; i++) ok.append(']');
        assertThat(MiniJson.parse(ok.toString())).isInstanceOf(List.class);
        for (int i = 0; i <= MiniJson.MAX_DEPTH; i++) deep.append("{\"k\":");
        deep.append("1");
        for (int i = 0; i <= MiniJson.MAX_DEPTH; i++) deep.append('}');
        assertThatThrownBy(() -> MiniJson.parse(deep.toString()))
                .isInstanceOf(JsonException.class).hasMessageContaining("nesting");
    }

    @Test
    void typedGettersRejectWrongTypesAndMissingKeys() throws Exception {
        Map<String, Object> o = MiniJson.parseObject("{\"n\": 1.5, \"s\": \"x\", \"big\": 1e20, \"o\": {}, \"a\": [\"x\"]}");
        assertThatThrownBy(() -> MiniJson.getString(o, "n")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> MiniJson.getDouble(o, "s")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> MiniJson.getBoolean(o, "s")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> MiniJson.getObject(o, "s")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> MiniJson.getArray(o, "s")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> MiniJson.getInt(o, "n")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> MiniJson.getInt(o, "big")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> MiniJson.getLong(o, "big")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> MiniJson.getLong(o, "n")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> MiniJson.getString(o, "missing")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> MiniJson.toDoubles(MiniJson.getArray(o, "a"))).isInstanceOf(JsonException.class);
        assertThat(MiniJson.getObject(o, "o")).isEmpty();
        assertThat(MiniJson.toDoubles(Arrays.<Object>asList(1.0, 2.5))).containsExactly(1.0, 2.5);
    }
}
