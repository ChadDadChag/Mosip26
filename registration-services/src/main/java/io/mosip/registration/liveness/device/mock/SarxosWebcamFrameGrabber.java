package io.mosip.registration.liveness.device.mock;

import com.github.sarxos.webcam.Webcam;
import com.github.sarxos.webcam.WebcamDiscoveryService;

import io.mosip.registration.liveness.api.DeviceCapabilities;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Real, hardware-backed {@link MockFrameSource.WebcamFrameGrabber} using
 * {@code com.github.sarxos:webcam-capture}. This is not a new dependency for the
 * project: {@code registration-services}' sibling module
 * {@code ref-impl/ref-document-scanner} already depends on the exact same library
 * and version (0.3.12) for its own webcam-backed document scanner
 * ({@code io.mosip.registration.ref.sarxos.WebCamSarxosImpl}), and this class
 * mirrors that already-working usage pattern (webcam selection by name,
 * {@code setCustomViewSizes}/{@code open()}/{@code getImage()}/{@code close()})
 * rather than introducing a different library or a new usage style.
 */
public final class SarxosWebcamFrameGrabber implements MockFrameSource.WebcamFrameGrabber {

    private final Webcam webcam;

    /** Uses the system's default webcam at its default resolution. */
    public SarxosWebcamFrameGrabber() throws IOException {
        this(null, null);
    }

    /**
     * @param webcamName    exact name of the webcam to use (as reported by
     *                      {@link Webcam#getWebcams()}), or {@code null} for the
     *                      system default
     * @param requestedSize requested capture resolution, or {@code null} to use
     *                      the webcam's own default view size
     */
    public SarxosWebcamFrameGrabber(String webcamName, Dimension requestedSize) throws IOException {
        Webcam selected;
        try {
            selected = (webcamName == null)
                    ? Webcam.getDefault()
                    : Webcam.getWebcams().stream()
                            .filter(w -> w.getName().equals(webcamName))
                            .findFirst()
                            .orElse(null);
        } catch (RuntimeException e) {
            // sarxos can throw unchecked WebcamException from device discovery.
            throw new IOException("Webcam discovery failed: " + e.getMessage(), e);
        }

        if (selected == null) {
            throw new IOException(webcamName == null
                    ? "No webcam is connected/available"
                    : "No webcam found with name: " + webcamName);
        }

        try {
            if (requestedSize != null) {
                selected.setCustomViewSizes(new Dimension[]{requestedSize});
                selected.setViewSize(requestedSize);
            }
        } catch (RuntimeException e) {
            // sarxos throws unchecked exceptions (e.g. IllegalArgumentException) for
            // unsupported sizes; surface as the documented checked failure instead.
            throw new IOException("Unsupported webcam resolution " + requestedSize + ": " + e.getMessage(), e);
        }

        this.webcam = selected;
    }

    /**
     * Opens the device on first use (and again after {@link #close()}), so the
     * camera is only held while a MockFrameSource is actually running and a
     * stop()/start() cycle works.
     */
    private void ensureOpen() throws IOException {
        if (webcam.isOpen()) {
            return;
        }
        try {
            webcam.getLock().disable(); // matches the existing WebCamSarxosImpl usage
            if (!webcam.open()) {
                throw new IOException("Webcam did not open: " + webcam.getName());
            }
        } catch (RuntimeException e) {
            // sarxos throws unchecked WebcamException for driver/device failures.
            throw new IOException("Failed to open webcam " + webcam.getName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public BufferedImage grabFrame() throws IOException {
        ensureOpen();
        BufferedImage image;
        try {
            image = webcam.getImage();
        } catch (RuntimeException e) {
            throw new IOException("Webcam capture failed: " + e.getMessage(), e);
        }
        if (image == null) {
            throw new IOException("Webcam returned no frame (device may have been disconnected): "
                    + webcam.getName());
        }
        return image;
    }

    /**
     * Resolutions the driver reports for this camera. The driver hands back decoded
     * images, so no wire format is reported, and sarxos only measures FPS once open.
     */
    @Override
    public DeviceCapabilities capabilities() {
        List<DeviceCapabilities.Resolution> resolutions = new ArrayList<>();
        try {
            Dimension[] sizes = webcam.getViewSizes();
            if (sizes != null) {
                for (Dimension size : sizes) {
                    if (size != null && size.width > 0 && size.height > 0) {
                        DeviceCapabilities.Resolution r = new DeviceCapabilities.Resolution(size.width, size.height);
                        if (!resolutions.contains(r)) {
                            resolutions.add(r);
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            return DeviceCapabilities.unknown();
        }
        return new DeviceCapabilities(Collections.<String>emptyList(), resolutions,
                Collections.<Integer>emptyList(), DeviceCapabilities.Origin.DECLARED);
    }

    @Override
    public void close() {
        if (webcam.isOpen()) {
            webcam.close();
        }
    }

    /**
     * F13b: stops sarxos' discovery service if it runs, before the JVM exits, so that sarxos' shutdown hook has less
     * left to do ({@link WebcamGrabbers#exit}). Never throws.
     */
    static void stopDiscovery() {
        try {
            WebcamDiscoveryService discovery = Webcam.getDiscoveryServiceRef();
            if (discovery != null && discovery.isRunning()) {
                discovery.stop();
            }
        } catch (RuntimeException | LinkageError e) {
            System.err.println("sarxos discovery stop failed: " + e);
        }
    }

    /** Lists the names of currently connected webcams, for diagnostics/device selection. */
    public static Optional<String> defaultWebcamName() {
        Webcam defaultWebcam = Webcam.getDefault();
        return defaultWebcam == null ? Optional.empty() : Optional.of(defaultWebcam.getName());
    }
}
