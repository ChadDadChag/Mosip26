package io.mosip.registration.liveness.analyzer.testing;

import io.mosip.registration.liveness.analyzer.detect.FaceDetection;
import io.mosip.registration.liveness.analyzer.integrity.MiniJson;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Golden fixtures written by {@code models/tools/make_golden.py}: lossless PNGs plus the reference pipeline's
 * JSON. A "_flipped" fixture is the decoded image mirrored horizontally in memory (there is no flipped PNG).
 */
public final class GoldenFixtures {

    public static final String[] NAMES = {"portrait_frontal", "portrait_frontal_flipped", "crew_multi", "crew_multi_flipped"};

    private GoldenFixtures() { }

    /** A decoded RGB888 image (row-major, no padding), the same layout as {@code Frame.rgb()}. */
    public static final class Image {
        public final byte[] rgb;
        public final int width, height;

        Image(byte[] rgb, int width, int height) {
            this.rgb = rgb;
            this.width = width;
            this.height = height;
        }
    }

    /** The image for a fixture name, mirrored when the name ends in "_flipped". */
    public static Image image(String name) throws IOException {
        boolean flipped = name.endsWith("_flipped");
        String base = flipped ? name.substring(0, name.length() - "_flipped".length()) : name;
        Image img = png(base + ".png");
        return flipped ? flip(img) : img;
    }

    public static Image png(String file) throws IOException {
        InputStream in = GoldenFixtures.class.getClassLoader().getResourceAsStream("golden/" + file);
        if (in == null) throw new IOException("missing golden/" + file);
        BufferedImage bi;
        try {
            bi = ImageIO.read(in);
        } finally {
            in.close();
        }
        int w = bi.getWidth(), h = bi.getHeight();
        byte[] rgb = new byte[w * h * 3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = bi.getRGB(x, y);
                int o = (y * w + x) * 3;
                rgb[o] = (byte) (argb >> 16);
                rgb[o + 1] = (byte) (argb >> 8);
                rgb[o + 2] = (byte) argb;
            }
        }
        return new Image(rgb, w, h);
    }

    public static Image flip(Image img) {
        byte[] out = new byte[img.rgb.length];
        for (int y = 0; y < img.height; y++) {
            for (int x = 0; x < img.width; x++) {
                int src = (y * img.width + x) * 3, dst = (y * img.width + (img.width - 1 - x)) * 3;
                out[dst] = img.rgb[src];
                out[dst + 1] = img.rgb[src + 1];
                out[dst + 2] = img.rgb[src + 2];
            }
        }
        return new Image(out, img.width, img.height);
    }

    public static Map<String, Object> json(String name) throws Exception {
        byte[] bytes = TestModels.read("golden/" + name + ".json");
        return MiniJson.parseObject(new String(bytes, StandardCharsets.UTF_8));
    }

    /** A detection object from the JSON ({"box": [x,y,w,h], "score": s, "kps": [[x,y] x 5]}). */
    public static FaceDetection detection(Map<String, Object> d) throws Exception {
        double[] box = MiniJson.toDoubles(MiniJson.getArray(d, "box"));
        List<Object> kps = MiniJson.getArray(d, "kps");
        double[] k = new double[10];
        for (int n = 0; n < 5; n++) {
            @SuppressWarnings("unchecked")
            double[] p = MiniJson.toDoubles((List<Object>) kps.get(n));
            k[2 * n] = p[0];
            k[2 * n + 1] = p[1];
        }
        return new FaceDetection(box[0], box[1], box[2], box[3], MiniJson.getDouble(d, "score"), k);
    }

    /** Population mean of the first {@code n} values (tensor_stats "mean"). */
    public static double mean(float[] values, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) sum += values[i];
        return sum / n;
    }
}
