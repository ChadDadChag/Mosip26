package io.mosip.registration.liveness.device.sbi;

import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.exception.RegistrationExceptionConstants;
import io.mosip.registration.liveness.api.DeviceCapabilities;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.device.util.CapabilityProbe;
import io.mosip.registration.mdm.dto.MdmBioDevice;
import io.mosip.registration.mdm.service.impl.MosipDeviceSpecificationFactory;
import io.mosip.registration.service.bio.BioService;

import java.io.IOException;

/**
 * Bridges existing MOSIP device discovery with the new FrameSource abstraction.
 * This class deliberately leaves the existing discovery/selection implementation
 * unchanged.
 */
public class SbiStreamFrameSourceFactory {

    private final MosipDeviceSpecificationFactory deviceSpecificationFactory;
    private final BioService bioService;
    private final long frameTimeoutMs;
    private final int frameStride;
    private final int maxReconnectAttempts;
    private final long reconnectBackoffMs;

    public SbiStreamFrameSourceFactory(MosipDeviceSpecificationFactory deviceSpecificationFactory,
                                       BioService bioService) {
        this(deviceSpecificationFactory, bioService, SbiStreamFrameSource.DEFAULT_FRAME_TIMEOUT_MS);
    }

    public SbiStreamFrameSourceFactory(MosipDeviceSpecificationFactory deviceSpecificationFactory,
                                       BioService bioService, long frameTimeoutMs) {
        this(deviceSpecificationFactory, bioService, frameTimeoutMs, SbiStreamFrameSource.DEFAULT_FRAME_STRIDE,
                SbiStreamFrameSource.DEFAULT_MAX_RECONNECT_ATTEMPTS, SbiStreamFrameSource.DEFAULT_RECONNECT_BACKOFF_MS);
    }

    /** Full stream policy; see {@link SbiStreamFrameSource}'s constructors for each parameter. */
    public SbiStreamFrameSourceFactory(MosipDeviceSpecificationFactory deviceSpecificationFactory,
                                       BioService bioService, long frameTimeoutMs, int frameStride,
                                       int maxReconnectAttempts, long reconnectBackoffMs) {
        if (deviceSpecificationFactory == null || bioService == null) {
            throw new IllegalArgumentException("Device factory and BioService are required");
        }
        this.deviceSpecificationFactory = deviceSpecificationFactory;
        this.bioService = bioService;
        this.frameTimeoutMs = frameTimeoutMs;
        this.frameStride = frameStride;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.reconnectBackoffMs = reconnectBackoffMs;
    }

    /**
     * Resolves the already-selected SBI device and creates a FrameSource for it.
     *
     * <p>Mirrors the existing availability check performed elsewhere in the
     * Registration Client (e.g. BioServiceImpl) before opening a stream, so a
     * device that has gone offline since discovery fails fast with the same
     * MDS_BIODEVICE_NOT_FOUND signal the rest of the app already uses.</p>
     */
    public FrameSource create(String modality) throws RegBaseCheckedException {
        MdmBioDevice device = deviceSpecificationFactory.getDeviceInfoByModality(modality);
        if (device == null || !deviceSpecificationFactory.isDeviceAvailable(device)) {
            throw new RegBaseCheckedException(RegistrationExceptionConstants.MDS_BIODEVICE_NOT_FOUND.getErrorCode(),
                    RegistrationExceptionConstants.MDS_BIODEVICE_NOT_FOUND.getErrorMessage());
        }
        return new SbiStreamFrameSource(bioService, device, modality, frameTimeoutMs, frameStride,
                maxReconnectAttempts, reconnectBackoffMs);
    }

    /**
     * Capability discovery for an SBI face device: MDS reports no resolution/FPS, so
     * this opens the stream briefly and reports what the device actually delivers.
     */
    public DeviceCapabilities probeCapabilities(String modality, int sampleFrames, long timeoutMs)
            throws RegBaseCheckedException, IOException, InterruptedException {
        return CapabilityProbe.probe(create(modality), sampleFrames, timeoutMs);
    }
}
