package io.mosip.registration.liveness.desktop;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;

import io.mosip.biometrics.util.ConvertRequestDto;
import io.mosip.biometrics.util.face.FaceDecoder;
import io.mosip.registration.liveness.api.Frame;

/**
 * Turns the face the SBI device captured (an ISO/IEC 19794-5 record, usually holding a JPEG 2000 image) into the
 * RGB888 {@link Frame} the face matcher compares with the liveness best frame. Uses the same MOSIP decoder the
 * capture screens already use to show the captured photo. Everything stays in memory.
 */
class CapturedFaceDecoder {

	/** Larger captures are scaled down: face binding needs about 112 px per face, not a full-resolution photo. */
	static final int MAX_SIDE = 1280;

	/** The captured face as RGB888, or null when the record cannot be decoded. */
	Frame decode(byte[] iso19794Record) {
		if (iso19794Record == null || iso19794Record.length == 0) {
			return null;
		}
		try {
			ConvertRequestDto request = new ConvertRequestDto();
			request.setVersion("ISO19794_5_2011");
			request.setInputBytes(iso19794Record);
			return toFrame(FaceDecoder.convertFaceISOToBufferedImage(request));
		} catch (Exception e) {
			// Includes the decoder's own exceptions: an undecodable capture fails binding (closed), never crashes.
			return null;
		}
	}

	static Frame toFrame(BufferedImage source) throws IOException {
		if (source == null) {
			return null;
		}
		BufferedImage img = scaleDown(source);
		int w = img.getWidth();
		int h = img.getHeight();
		int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
		byte[] rgb = new byte[w * h * 3];
		for (int i = 0, o = 0; i < argb.length; i++) {
			int p = argb[i];
			rgb[o++] = (byte) (p >> 16);
			rgb[o++] = (byte) (p >> 8);
			rgb[o++] = (byte) p;
		}
		return new Frame(rgb, w, h, System.currentTimeMillis(), 0, "sbi-capture");
	}

	private static BufferedImage scaleDown(BufferedImage img) {
		int longest = Math.max(img.getWidth(), img.getHeight());
		if (longest <= MAX_SIDE) {
			return img;
		}
		double s = (double) MAX_SIDE / longest;
		int w = Math.max(1, (int) Math.round(img.getWidth() * s));
		int h = Math.max(1, (int) Math.round(img.getHeight() * s));
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(img, 0, 0, w, h, null);
		g.dispose();
		return out;
	}
}
