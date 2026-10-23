package io.mosip.registration.liveness.engine;

import io.mosip.registration.liveness.api.ErrorCode;
import io.mosip.registration.liveness.api.LivenessEngineException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;

/**
 * Loads and integrity-checks the identity/version record for a {@code FaceAnalyzer}
 * implementation, and exposes its version string for
 * {@link io.mosip.registration.liveness.api.FaceAnalyzer#modelVersion()}.
 *
 * <p>This models the "model packaging and versioning" requirement using the same
 * load-then-verify-checksum pattern a real ONNX model manifest would use, without
 * requiring an actual trained model binary: a small properties resource records the
 * algorithm's identity and version, and a sibling manifest resource records the
 * properties file's expected SHA-256 checksum, computed once at packaging time. On
 * load, the checksum is recomputed and compared; any mismatch or missing resource
 * fails closed with {@link ErrorCode#MODEL_INTEGRITY_FAILED}, exactly as a corrupted
 * or tampered model file should.</p>
 */
final class ModelManifest {

    private final String version;
    private final String name;

    private ModelManifest(String version, String name) {
        this.version = version;
        this.name = name;
    }

    String version() {
        return version;
    }

    String name() {
        return name;
    }

    /**
     * Loads {@code propertiesResourcePath} and verifies its SHA-256 against the
     * {@code sha256=} value in {@code manifestResourcePath}.
     *
     * @throws LivenessEngineException with {@link ErrorCode#MODEL_INTEGRITY_FAILED}
     *         if either resource is missing, unreadable, malformed, or the checksum
     *         does not match.
     */
    static ModelManifest loadAndVerify(String propertiesResourcePath, String manifestResourcePath)
            throws LivenessEngineException {
        byte[] propertiesBytes = readResource(propertiesResourcePath);
        byte[] manifestBytes = readResource(manifestResourcePath);

        String expectedSha256 = extractSha256(new String(manifestBytes, StandardCharsets.UTF_8));
        String actualSha256 = sha256Hex(propertiesBytes);

        if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
            throw new LivenessEngineException(ErrorCode.MODEL_INTEGRITY_FAILED,
                    "Checksum mismatch for " + propertiesResourcePath
                            + ": expected " + expectedSha256 + " but computed " + actualSha256);
        }

        Properties properties = new Properties();
        try {
            properties.load(new java.io.ByteArrayInputStream(propertiesBytes));
        } catch (IOException e) {
            throw new LivenessEngineException(ErrorCode.MODEL_INTEGRITY_FAILED,
                    "Could not parse " + propertiesResourcePath, e);
        }

        String version = properties.getProperty("model.version");
        String name = properties.getProperty("model.name");
        if (version == null || version.trim().isEmpty()) {
            throw new LivenessEngineException(ErrorCode.MODEL_INTEGRITY_FAILED,
                    propertiesResourcePath + " is missing model.version");
        }

        return new ModelManifest(version.trim(), name == null ? "" : name.trim());
    }

    private static byte[] readResource(String resourcePath) throws LivenessEngineException {
        try (InputStream in = ModelManifest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new LivenessEngineException(ErrorCode.MODEL_INTEGRITY_FAILED,
                        "Missing required resource: " + resourcePath);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new LivenessEngineException(ErrorCode.MODEL_INTEGRITY_FAILED,
                    "Failed reading resource: " + resourcePath, e);
        }
    }

    private static String extractSha256(String manifestText) throws LivenessEngineException {
        for (String line : manifestText.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("sha256=")) {
                return trimmed.substring("sha256=".length()).trim();
            }
        }
        throw new LivenessEngineException(ErrorCode.MODEL_INTEGRITY_FAILED,
                "Manifest is missing a sha256= line");
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every standard JVM (JLS platform requirement).
            throw new UncheckedIOException(new IOException("SHA-256 unavailable", e));
        }
    }
}
