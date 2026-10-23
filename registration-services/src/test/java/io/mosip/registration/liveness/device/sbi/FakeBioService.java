package io.mosip.registration.liveness.device.sbi;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.mdm.dto.MdmBioDevice;
import io.mosip.registration.service.bio.BioService;

import java.io.IOException;
import java.io.InputStream;

/**
 * BioService test double whose {@code getStream(device, modality)} opens a fresh
 * stream from {@code factory} on every call (so reconnects get a new stream).
 *
 * <p>The real BioService has many methods, so it cannot be written as a lambda.
 * Only {@code Mockito.mock/when/any/anyString} are used, which exist in both the
 * Mockito 1.x and 2.x jars on the registration-services test classpath.</p>
 */
final class FakeBioService {

    interface StreamFactory {
        InputStream open() throws IOException;
    }

    private FakeBioService() {
    }

    static BioService returning(StreamFactory factory) throws RegBaseCheckedException {
        BioService bioService = mock(BioService.class);
        when(bioService.getStream(any(MdmBioDevice.class), anyString()))
                .thenAnswer(invocation -> factory.open());
        return bioService;
    }

    /**
     * Serves {@code bytes}, then blocks like a live device that has not sent the next
     * frame yet, until closed. Avoids a natural EOF racing the test's assertions.
     */
    static InputStream thenBlock(byte[] bytes) {
        return new InputStream() {
            private volatile boolean closed;
            private int pos;

            @Override
            public int read() throws IOException {
                if (pos < bytes.length) {
                    return bytes[pos++] & 0xFF;
                }
                while (!closed) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", e);
                    }
                }
                throw new IOException("closed");
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }
}
