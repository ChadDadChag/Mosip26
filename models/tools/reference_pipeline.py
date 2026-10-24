"""Part 2 reference pipeline: the EXECUTABLE SPEC that the Java analyzer must match.

    python -I models/tools/reference_pipeline.py IMAGE [--flip] [--json OUT]
    python -I models/tools/reference_pipeline.py --session      (session moire cap self-test)

All math is numpy. cv2 is used only by imread_bgr (decoding) and by the webcam script for
capture and display. Each step's docstring is the formula the Java port mirrors.

Coordinate conventions used everywhere below
  * Frame coordinates are CONTINUOUS: pixel (col i, row j) covers [i, i+1) x [j, j+1), so its
    centre is (i + 0.5, j + 0.5). Image y points DOWN. Boxes are [x, y, w, h].
  * "round" means round half up: floor(v + 0.5). Integer casts of non-negative values truncate.
  * Geometry is computed in float64 (Java: double); model tensors are float32.
"""
import argparse
import json
import math
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort

MODELS = Path(__file__).resolve().parent.parent
YUNET_FILE = "yunet_2026may.onnx"
MESH_FILE = "face_mesh_v2.onnx"
PAD_V2_FILE = "minifasnet_v2_s2.7.onnx"
PAD_V1SE_FILE = "minifasnet_v1se_s4.0.onnx"
SFACE_FILE = "face_recognition_sface_2021dec.onnx"

YUNET_TARGET_WIDTH = 320
YUNET_STRIDES = (8, 16, 32)
YUNET_SCORE_THRESHOLD = 0.7
YUNET_NMS_IOU = 0.3
FACE_COUNT_AREA_RATIO = 0.2
MESH_SIZE = 256
MESH_POINTS = 478
MESH_INPUT, MESH_LANDMARKS, MESH_FLAG = "input_12", "Identity", "Identity_1"
MESH_ROI_SCALE = 1.5
MESH_FLAG_THRESHOLD = 0.5
# Calibrated constant subtracted from the raw mesh pitch: the median raw pitch of 49 frontal
# official NASA portraits (IQR 6.3 deg), see calibrate_pitch.py and pitch_calibration_urls.txt.
# The JSON keeps the raw value as pitchRaw.
PITCH_BIAS_DEG = -9.5
PAD_SIZE = 80
PAD_V2_SCALE = 2.7
PAD_V1SE_SCALE = 4.0
PAD_REAL_CLASS = 1

# Quality (initial thresholds; Phase 5 calibrates live).
QUALITY_SIZE = 112
Q_TOO_DARK_LUMA = 40.0        # lumaMean below -> TOO_DARK
Q_TOO_BRIGHT_LUMA = 220.0     # lumaMean above -> TOO_BRIGHT
Q_BLUR_LAP_VAR = 15.0         # lapVar below -> BLURRY (blur study in models/README.md)
Q_MIN_AREA_FRAC = 0.04        # areaFrac below -> FACE_TOO_SMALL
Q_MIN_FACE_WIDTH_PX = 80.0    # face width below -> FACE_TOO_SMALL
Q_MAX_AREA_FRAC = 0.60        # areaFrac above -> FACE_TOO_LARGE
Q_MAX_CENTRE_OFFSET = 0.25    # centreDx or centreDy above -> FACE_OFF_CENTER
Q_MAX_CLIPPED_FRACTION = 0.30 # clipped_fraction above -> TOO_BRIGHT (auto-exposure keeps lumaMean near 110 even when blown out)

# Replay cues. Glare is audit only; moire feeds the session cap below.
MOIRE_SIZE = 64
MOIRE_R_MIN, MOIRE_R_MAX = 8, 31
MOIRE_EPS = 1e-6
GLARE_LEVEL = 250.0

# Session moire cap (Phase 6 decision D-PAD-3; see session_scores). None switches a threshold off.
MOIRE_CAP_THRESHOLD = 1.40    # rolling median moire at or above -> score = min(score, MOIRE_CAP_SCORE)
MOIRE_CAP_SCORE = 0.60        # below passiveThreshold 0.85 (forces a challenge), above padRejectThreshold 0.30
MOIRE_WINDOW = 10             # single-face frames in the rolling median
MOIRE_MIN_FRAMES = 3          # values needed before the cap or veto can apply
MOIRE_VETO_THRESHOLD = None   # rolling median at or above -> score 0 (off until multi-device data exists)

# Mesh indices, subject's point of view. Eye order is the contract's p1..p6:
# [outer corner, upper-outer, upper-inner, inner corner, lower-inner, lower-outer].
LEFT_EYE = (263, 387, 385, 362, 380, 373)
RIGHT_EYE = (33, 160, 158, 133, 153, 144)
MOUTH_LEFT, MOUTH_RIGHT = 291, 61
MOUTH_UPPER_INNER, MOUTH_LOWER_INNER = 13, 14
NOSE_TIP, CHIN, FOREHEAD = 1, 152, 10
LEFT_EYE_OUTER, RIGHT_EYE_OUTER = 263, 33
LEFT_IRIS, RIGHT_IRIS = 473, 468  # V2 only (points 468..477)

# Face binding (F1): OpenCV FaceRecognizerSF with the zoo's SFace model, mirrored from OpenCV 5.0.0
# modules/objdetect/src/face_recognize.cpp (commit 40738fb, the opencv-python 5.0.0 build used here).
SFACE_SIZE = 112
SFACE_INPUT, SFACE_OUTPUT = "data", "fc1"
# face_recognize.cpp:80, the 112 x 112 ArcFace template: right eye, left eye, nose tip, right and left mouth corner
# (the YuNet keypoint order), and :85, its mean as written in the source.
SFACE_TEMPLATE = ((38.2946, 51.6963), (73.5318, 51.5014), (56.0252, 71.7366), (41.5493, 92.3655), (70.7299, 92.2041))
SFACE_TEMPLATE_MEAN = (56.0262, 71.9008)
SFACE_COSINE_THRESHOLD = 0.363  # opencv_zoo models/face_recognition_sface/sface.py:24 (match when cosine >= 0.363)


# ---------------------------------------------------------------------------------------------
# Image helpers
# ---------------------------------------------------------------------------------------------

def imread_bgr(path):
    """Decode an image file to uint8 HxWx3 BGR (cv2 is used for decoding only)."""
    import cv2
    img = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if img is None:
        raise FileNotFoundError(path)
    return img


def round_half_up(v):
    return int(math.floor(v + 0.5))


def sample_affine(img, M, out_w, out_h):
    """Bilinear resampling through an affine map. Resize, crop and rotated crop are special cases.

    M is 2x3 and maps DESTINATION continuous coords to SOURCE continuous coords. For each
    destination pixel (i, j):
        p      = (i + 0.5, j + 0.5)
        (qx, qy) = M . (p.x, p.y, 1)
        x  = clamp(qx - 0.5, 0, W - 1)          y  = clamp(qy - 0.5, 0, H - 1)   (replicate border)
        x0 = floor(x)  x1 = min(x0 + 1, W - 1)  y0 = floor(y)  y1 = min(y0 + 1, H - 1)
        fx = x - x0    fy = y - y0
        out = (1-fy)*((1-fx)*I[y0,x0] + fx*I[y0,x1]) + fy*((1-fx)*I[y1,x0] + fx*I[y1,x1])
    A plain resize W x H -> w x h is M = [[W/w, 0, 0], [0, H/h, 0]], which gives the usual
    half-pixel rule src = (dst + 0.5) * scale - 0.5. Coordinates and weights are float64; the
    result is float32 with the same channel count as img.
    """
    h_src, w_src = img.shape[:2]
    src = img[:, :, None] if img.ndim == 2 else img
    px = np.arange(out_w, dtype=np.float64) + 0.5
    py = np.arange(out_h, dtype=np.float64) + 0.5
    gx, gy = np.meshgrid(px, py)
    qx = M[0][0] * gx + M[0][1] * gy + M[0][2]
    qy = M[1][0] * gx + M[1][1] * gy + M[1][2]
    x = np.clip(qx - 0.5, 0.0, w_src - 1)
    y = np.clip(qy - 0.5, 0.0, h_src - 1)
    x0 = np.floor(x).astype(np.int64)
    y0 = np.floor(y).astype(np.int64)
    x1 = np.minimum(x0 + 1, w_src - 1)
    y1 = np.minimum(y0 + 1, h_src - 1)
    fx = (x - x0)[:, :, None]
    fy = (y - y0)[:, :, None]
    px = lambda yy, xx: src[yy, xx].astype(np.float64)  # noqa: E731  (gather, then widen)
    top = (1 - fx) * px(y0, x0) + fx * px(y0, x1)
    bottom = (1 - fx) * px(y1, x0) + fx * px(y1, x1)
    out = (1 - fy) * top + fy * bottom
    if img.ndim == 2:
        out = out[:, :, 0]
    return out.astype(np.float32)


def resize_matrix(src_w, src_h, out_w, out_h):
    return [[src_w / out_w, 0.0, 0.0], [0.0, src_h / out_h, 0.0]]


def crop_matrix(left, top, crop_w, crop_h, out_w, out_h):
    """Inclusive pixel crop cols left..left+crop_w-1, rows top..top+crop_h-1, resized to out_w x out_h."""
    return [[crop_w / out_w, 0.0, float(left)], [0.0, crop_h / out_h, float(top)]]


def apply_affine(M, x, y):
    return (M[0][0] * x + M[0][1] * y + M[0][2], M[1][0] * x + M[1][1] * y + M[1][2])


def tensor_stats(t):
    """mean, std (population) and the first 8 values in memory order, for tracing parity bugs."""
    flat = np.asarray(t, dtype=np.float64).ravel()
    return {"shape": list(np.shape(t)), "mean": float(flat.mean()), "std": float(flat.std()),
            "first8": [float(v) for v in flat[:8]]}


def softmax(v):
    v = np.asarray(v, dtype=np.float64)
    e = np.exp(v - v.max())
    return e / e.sum()


def sigmoid(v):
    return 1.0 / (1.0 + math.exp(-v))


def iou(a, b):
    """IoU of two [x, y, w, h] boxes (same formula as FaceGeometry.iou)."""
    x1, y1 = max(a[0], b[0]), max(a[1], b[1])
    x2, y2 = min(a[0] + a[2], b[0] + b[2]), min(a[1] + a[3], b[1] + b[3])
    inter = max(0.0, x2 - x1) * max(0.0, y2 - y1)
    union = a[2] * a[3] + b[2] * b[3] - inter
    return inter / union if union > 0 else 0.0


def new_session(path, threads=2):
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = threads
    opts.log_severity_level = 3  # errors only: SFace lists its initializers as graph inputs, one warning each
    return ort.InferenceSession(str(path), opts, providers=["CPUExecutionProvider"])


# ---------------------------------------------------------------------------------------------
# YuNet face detector
# ---------------------------------------------------------------------------------------------

class YuNet:
    """YuNet 2026may: symbolic H/W, input "input" NCHW float32 BGR raw 0..255."""

    def __init__(self, path, threads=2):
        self.session = new_session(path, threads)

    @staticmethod
    def prepare(bgr):
        """Downscale and pad the frame for YuNet.

        s  = min(1, 320 / W);  rw = round(W * s);  rh = round(H * s)
        resized = sample_affine(frame, resize_matrix(W, H, rw, rh), rw, rh)   (s == 1: exact copy)
        padW = ceil(rw / 32) * 32;  padH = ceil(rh / 32) * 32; zero-pad bottom and right.
        tensor = [1, 3, padH, padW] float32, channels B, G, R, raw 0..255.
        """
        h, w = bgr.shape[:2]
        s = min(1.0, YUNET_TARGET_WIDTH / w)
        rw, rh = round_half_up(w * s), round_half_up(h * s)
        resized = sample_affine(bgr, resize_matrix(w, h, rw, rh), rw, rh)
        pad_w, pad_h = -(-rw // 32) * 32, -(-rh // 32) * 32
        tensor = np.zeros((1, 3, pad_h, pad_w), dtype=np.float32)
        tensor[0, :, :rh, :rw] = resized.transpose(2, 0, 1)
        info = {"scale": s, "resizedW": rw, "resizedH": rh, "paddedW": pad_w, "paddedH": pad_h}
        return tensor, info

    @staticmethod
    def decode(outputs, pad_w, pad_h):
        """Decode as OpenCV FaceDetectorYN::postProcess, then greedy NMS.

        For stride in (8, 16, 32): cols = padW / stride, rows = padH / stride; anchor
        idx = r * cols + c (r = row, c = col).
            score = sqrt(clamp(cls[idx], 0, 1) * clamp(obj[idx], 0, 1)); keep if score >= 0.7
            cx = (c + b0) * stride      cy = (r + b1) * stride
            w  = exp(b2) * stride       h  = exp(b3) * stride
            box = [cx - w/2, cy - h/2, w, h]
            kp n (0..4) = ((k[2n] + c) * stride, (k[2n+1] + r) * stride)
              0 = subject's right eye, 1 = subject's left eye, 2 = nose tip,
              3 = subject's right mouth corner, 4 = subject's left mouth corner
        NMS: stable sort by score descending (ties keep stride-then-idx order); walk the list and
        keep a face unless its IoU with an already-kept face is > 0.3.
        Coordinates are in YuNet-input pixels here.
        """
        faces = []
        for stride in YUNET_STRIDES:
            cols, rows = pad_w // stride, pad_h // stride
            cls = outputs[f"cls_{stride}"][0, :, 0]
            obj = outputs[f"obj_{stride}"][0, :, 0]
            bbox = outputs[f"bbox_{stride}"][0]
            kps = outputs[f"kps_{stride}"][0]
            scores = np.sqrt(np.clip(cls.astype(np.float64), 0, 1) * np.clip(obj.astype(np.float64), 0, 1))
            for idx in np.flatnonzero(scores >= YUNET_SCORE_THRESHOLD):  # ascending idx, as a plain loop would
                score = float(scores[idx])
                r, c = divmod(int(idx), cols)
                b = bbox[idx].astype(np.float64)
                cx, cy = (c + b[0]) * stride, (r + b[1]) * stride
                bw, bh = math.exp(b[2]) * stride, math.exp(b[3]) * stride
                k = kps[idx].astype(np.float64)
                faces.append({"box": [cx - bw / 2, cy - bh / 2, bw, bh], "score": score,
                              "kps": [[(k[2 * n] + c) * stride, (k[2 * n + 1] + r) * stride] for n in range(5)]})
        faces.sort(key=lambda f: -f["score"])  # Python's sort is stable
        kept = []
        for f in faces:
            if all(iou(f["box"], k["box"]) <= YUNET_NMS_IOU for k in kept):
                kept.append(f)
        return kept

    def detect(self, bgr, debug=None):
        """Faces in frame coordinates (every YuNet-input coordinate divided by s)."""
        tensor, info = self.prepare(bgr)
        names = [o.name for o in self.session.get_outputs()]
        outputs = dict(zip(names, self.session.run(names, {"input": tensor})))
        faces = self.decode(outputs, info["paddedW"], info["paddedH"])
        s = info["scale"]
        for f in faces:
            f["box"] = [v / s for v in f["box"]]
            f["kps"] = [[x / s, y / s] for x, y in f["kps"]]
        if debug is not None:
            debug["yunet"] = info
            debug["yunetTensor"] = tensor_stats(tensor)
        return faces


def count_faces(faces):
    """faceCount = faces whose box area >= 0.2 x the largest area; primary = the largest
    (first in NMS order on an exact tie). Returns (faceCount, primary or None)."""
    if not faces:
        return 0, None
    areas = [f["box"][2] * f["box"][3] for f in faces]
    largest = max(areas)
    primary = faces[areas.index(largest)]
    return sum(1 for a in areas if a >= FACE_COUNT_AREA_RATIO * largest), primary


# ---------------------------------------------------------------------------------------------
# Face mesh (MediaPipe FaceMesh V2: 478 points, 256 x 256 input)
# ---------------------------------------------------------------------------------------------

def mesh_roi(face):
    """Rotated square ROI from the YuNet box and eye keypoints (MediaPipe's tracking convention:
    1.5x, square on the long side, rotated so the eyes are level).

    R = kp0 (subject's right eye), L = kp1 (subject's left eye)
    theta = atan2(L.y - R.y, L.x - R.x)
    (cx, cy) = box centre = (x + w/2, y + h/2);   S = 1.5 * max(w, h)
    """
    x, y, w, h = face["box"]
    (rx, ry), (lx, ly) = face["kps"][0], face["kps"][1]
    return {"cx": x + w / 2, "cy": y + h / 2, "S": MESH_ROI_SCALE * max(w, h),
            "theta": math.atan2(ly - ry, lx - rx)}


def mesh_matrix(roi, size=MESH_SIZE):
    """Mesh-input continuous coords (u, v) in [0, N] (N = 256) -> frame continuous coords (X, Y):
        dx = (u/N - 0.5) * S        dy = (v/N - 0.5) * S
        X  = cx + cos(theta)*dx - sin(theta)*dy
        Y  = cy + sin(theta)*dx + cos(theta)*dy
    Used both to sample the crop and to project landmarks back."""
    c, s, S = math.cos(roi["theta"]), math.sin(roi["theta"]), roi["S"]
    k = S / size
    return [[c * k, -s * k, roi["cx"] - c * S / 2 + s * S / 2],
            [s * k, c * k, roi["cy"] - s * S / 2 - c * S / 2]]


def mesh_input(bgr, roi, size=MESH_SIZE):
    """[1, N, N, 3] float32 NHWC, channels R, G, B, values in [0, 1]:
    sample_affine(frame, mesh_matrix(roi), N, N), reorder BGR -> RGB, divide by 255."""
    crop = sample_affine(bgr, mesh_matrix(roi, size), size, size)
    return (crop[:, :, ::-1] / np.float32(255.0))[None].astype(np.float32)


class FaceMesh:
    """FaceMesh V2: input "input_12" [1,256,256,3]; outputs "Identity" (1434 = 478 x (x, y, z)
    in 256-px units) and "Identity_1" (face-presence logit). The third output, "Identity_2",
    is not used (MediaPipe's graph reads only the first two)."""

    def __init__(self, path, threads=2, size=MESH_SIZE, points=MESH_POINTS,
                 names=(MESH_INPUT, MESH_LANDMARKS, MESH_FLAG)):
        self.session = new_session(path, threads)
        self.size, self.points, self.names = size, points, names

    def run(self, tensor):
        """Returns (raw points (478, 3) in mesh-input units, flag = sigmoid(logit))."""
        inp, lm_name, flag_name = self.names
        lm, logit = self.session.run([lm_name, flag_name], {inp: tensor})
        return lm.reshape(self.points, 3).astype(np.float64), sigmoid(float(logit.ravel()[0]))


def back_project(raw, roi, size=MESH_SIZE):
    """Raw mesh points -> frame coords: (X, Y) = mesh_matrix(roi) . (x, y, 1);  Z = (z / N) * S."""
    M = mesh_matrix(roi, size)
    out = np.empty_like(raw)
    for n, (x, y, z) in enumerate(raw):
        out[n, 0], out[n, 1] = apply_affine(M, x, y)
        out[n, 2] = z / size * roi["S"]
    return out


def named_points(pts):
    """Contract FaceLandmarks from the frame-space mesh points ({x, y} only). Iris centres are
    points 473 (subject's left) and 468 (subject's right) when the mesh has 478 points."""
    p = lambda i: [float(pts[i, 0]), float(pts[i, 1])]  # noqa: E731
    has_iris = len(pts) > LEFT_IRIS
    return {"leftEye": [p(i) for i in LEFT_EYE], "rightEye": [p(i) for i in RIGHT_EYE],
            "mouthLeft": p(MOUTH_LEFT), "mouthRight": p(MOUTH_RIGHT),
            "mouthUpperInner": p(MOUTH_UPPER_INNER), "mouthLowerInner": p(MOUTH_LOWER_INNER),
            "noseTip": p(NOSE_TIP), "chin": p(CHIN),
            "leftIris": p(LEFT_IRIS) if has_iris else None, "rightIris": p(RIGHT_IRIS) if has_iris else None}


def eye_aspect_ratio(eye):
    """EAR = (|p2 - p6| + |p3 - p5|) / (2 |p1 - p4|) on 2-D points (as FaceGeometry.eyeAspectRatio)."""
    d = lambda a, b: math.hypot(a[0] - b[0], a[1] - b[1])  # noqa: E731
    width = d(eye[0], eye[3])
    return (d(eye[1], eye[5]) + d(eye[2], eye[4])) / (2 * width) if width > 0 else float("nan")


def head_pose(pts):
    """Yaw, pitch, roll in degrees from the 3-D mesh.

    u_n = (X_n, -Y_n, -Z_n)                 x right, y up, z toward the camera
    ex  = normalize(u[263] - u[33])         subject's right outer eye corner -> left outer corner
    t   = u[10] - u[152]                    chin -> forehead
    ey  = normalize(t - (t . ex) ex)
    ez  = ex x ey
    yaw   = deg(atan2(ez.x, ez.z))                      + = subject turned to THEIR left
    pitch = deg(atan2(ez.y, sqrt(ez.x^2 + ez.z^2)))     + = subject looks UP (raw; the analyzer
                                                          reports pitch - PITCH_BIAS_DEG)
    roll  = deg(atan2(ex.y, ex.x))                      + = subject's left eye higher
    """
    u = np.stack([pts[:, 0], -pts[:, 1], -pts[:, 2]], axis=1)
    ex = u[LEFT_EYE_OUTER] - u[RIGHT_EYE_OUTER]
    ex /= np.linalg.norm(ex)
    t = u[FOREHEAD] - u[CHIN]
    ey = t - np.dot(t, ex) * ex
    ey /= np.linalg.norm(ey)
    ez = np.cross(ex, ey)
    yaw = math.degrees(math.atan2(ez[0], ez[2]))
    pitch = math.degrees(math.atan2(ez[1], math.hypot(ez[0], ez[2])))
    roll = math.degrees(math.atan2(ex[1], ex[0]))
    return yaw, pitch, roll


# ---------------------------------------------------------------------------------------------
# Silent-Face PAD (MiniFASNet V2 @ 2.7, V1SE @ 4.0)
# ---------------------------------------------------------------------------------------------

def get_new_box(src_w, src_h, bbox, scale):
    """Silent-Face CropImage._get_new_box, verbatim. bbox = [x, y, w, h] integers.
    Returns inclusive (left_top_x, left_top_y, right_bottom_x, right_bottom_y)."""
    x = bbox[0]
    y = bbox[1]
    box_w = bbox[2]
    box_h = bbox[3]

    scale = min((src_h - 1) / box_h, min((src_w - 1) / box_w, scale))

    new_width = box_w * scale
    new_height = box_h * scale
    center_x, center_y = box_w / 2 + x, box_h / 2 + y

    left_top_x = center_x - new_width / 2
    left_top_y = center_y - new_height / 2
    right_bottom_x = center_x + new_width / 2
    right_bottom_y = center_y + new_height / 2

    if left_top_x < 0:
        right_bottom_x -= left_top_x
        left_top_x = 0

    if left_top_y < 0:
        right_bottom_y -= left_top_y
        left_top_y = 0

    if right_bottom_x > src_w - 1:
        left_top_x -= right_bottom_x - src_w + 1
        right_bottom_x = src_w - 1

    if right_bottom_y > src_h - 1:
        left_top_y -= right_bottom_y - src_h + 1
        right_bottom_y = src_h - 1

    return int(left_top_x), int(left_top_y), int(right_bottom_x), int(right_bottom_y)


def pad_box(face_box):
    """Integer box fed to get_new_box: [floor(x), floor(y), round(w), round(h)]."""
    x, y, w, h = face_box
    return [int(math.floor(x)), int(math.floor(y)), round_half_up(w), round_half_up(h)]


def pad_input(bgr, crop_box):
    """[1, 3, 80, 80] float32 NCHW, channels B, G, R, raw 0..255 (no /255, no mean/std):
    inclusive crop (ltx..rbx, lty..rby) resized to 80 x 80 with sample_affine."""
    ltx, lty, rbx, rby = crop_box
    M = crop_matrix(ltx, lty, rbx - ltx + 1, rby - lty + 1, PAD_SIZE, PAD_SIZE)
    crop = sample_affine(bgr, M, PAD_SIZE, PAD_SIZE)
    return crop.transpose(2, 0, 1)[None].astype(np.float32)


class MiniFasNet:
    """Input "input" [1,3,80,80]; output "output" [1,3] logits; softmax index 1 = real."""

    def __init__(self, path, scale, threads=2):
        self.session = new_session(path, threads)
        self.scale = scale

    def run(self, tensor):
        logits = self.session.run(["output"], {"input": tensor})[0][0].astype(np.float64)
        return logits, softmax(logits)


# ---------------------------------------------------------------------------------------------
# Face binding (F1): SFace embeddings, as OpenCV's FaceRecognizerSF
# ---------------------------------------------------------------------------------------------

FLT_MIN = 1.1754943508222875e-38


def sface_transform(kps):
    """2x3 similarity transform from the 5 YuNet keypoints (frame) to the 112 x 112 template, exactly as
    FaceRecognizerSFImpl::getSimilarityTransformMatrix (face_recognize.cpp:79-189), an Umeyama fit. The C++ keeps
    points, means and de-meaned points in float and accumulates in double; so does this.

        src_mean = (sum of the 5 src x, then y, added left to right in float) / 5          (:81-84)
        dst_mean = SFACE_TEMPLATE_MEAN, the constants written in the source              (:85)
        A[r][c]  = (1/5) sum_i dst_demean[i][r] * src_demean[i][c]                        (:103-115)
        d = (1, 1); d[1] = -1 when det(A) < 0                                            (:117-120)
        U, S, Vt = svd(A), singular values descending (cv::SVD::compute)                 (:123)
        rank = number of S > max(S) * 2 * FLT_MIN                                        (:124-130)
        R = U diag(d) Vt; but for rank 1 with det(U) det(Vt) > 0, R = U Vt                (:135-168)
        var = (1/5) sum src_demean_x^2 + (1/5) sum src_demean_y^2                        (:169-176)
        scale = (S0 d0 + S1 d1) / var                                                    (:177)
        M = [scale R | dst_mean - scale R src_mean]                                      (:178-187)
    M maps SOURCE pixel indices to template pixel indices (OpenCV's convention for warpAffine).
    """
    f32 = np.float32
    src = np.asarray(kps, dtype=np.float32).reshape(5, 2)
    dst = np.asarray(SFACE_TEMPLATE, dtype=np.float32)
    src_mean = []
    for k in range(2):
        s = src[0, k]
        for i in range(1, 5):
            s = f32(s + src[i, k])
        src_mean.append(f32(s / f32(5)))
    src_mean = np.asarray(src_mean, dtype=np.float32)
    dst_mean = np.asarray(SFACE_TEMPLATE_MEAN, dtype=np.float32)
    src_demean = (src - src_mean).astype(np.float32)
    dst_demean = (dst - dst_mean).astype(np.float32)

    def mean_of_products(p, q):
        acc = 0.0
        for i in range(5):
            acc += float(f32(p[i] * q[i]))
        return acc / 5

    A = np.array([[mean_of_products(dst_demean[:, 0], src_demean[:, 0]),
                   mean_of_products(dst_demean[:, 0], src_demean[:, 1])],
                  [mean_of_products(dst_demean[:, 1], src_demean[:, 0]),
                   mean_of_products(dst_demean[:, 1], src_demean[:, 1])]], dtype=np.float64)
    d = [1.0, 1.0]
    if A[0, 0] * A[1, 1] - A[0, 1] * A[1, 0] < 0:
        d[1] = -1.0
    u, s, vt = np.linalg.svd(A)
    tol = max(s[0], s[1]) * 2 * FLT_MIN
    rank = int(s[0] > tol) + int(s[1] > tol)
    if rank == 1 and np.linalg.det(u) * np.linalg.det(vt) > 0:
        R = u @ vt
    elif rank == 1:
        R = u @ np.diag([d[0], -1.0]) @ vt
    else:
        R = u @ np.diag(d) @ vt
    var = mean_of_products(src_demean[:, 0], src_demean[:, 0]) + mean_of_products(src_demean[:, 1], src_demean[:, 1])
    scale = 1.0 / var * (s[0] * d[0] + s[1] * d[1])
    ts = R @ src_mean.astype(np.float64)
    t = dst_mean.astype(np.float64) - scale * ts
    return [[scale * R[0, 0], scale * R[0, 1], t[0]], [scale * R[1, 0], scale * R[1, 1], t[1]]]


def warp_affine_cv(img, M, out_w, out_h):
    """cv::warpAffine(img, out, M, (out_w, out_h), INTER_LINEAR) on an 8-bit image, as face_recognize.cpp:54 calls
    it (default BORDER_CONSTANT 0). Unlike sample_affine, M maps SOURCE pixel indices to DESTINATION pixel indices
    and pixel centres are at integer coordinates:
        Minv = cv::invertAffineTransform(M)
        (X, Y) = Minv . (i, j, 1) for destination pixel (i, j)
        x0 = floor(X), y0 = floor(Y), fx = X - x0, fy = Y - y0; a neighbour outside the image counts as 0
        out = (1-fy)((1-fx) I[y0,x0] + fx I[y0,x0+1]) + fy((1-fx) I[y0+1,x0] + fx I[y0+1,x0+1])
        rounded half up to 0..255 (the 8-bit result OpenCV hands to blobFromImage).
    OpenCV quantises the sub-pixel position to 1/32 pixel; this float version does not. make_golden.py compares
    the crops and the features with cv2's."""
    a, b, c = M[0]
    d, e, f = M[1]
    det = a * e - b * d
    det = 1.0 / det if det != 0 else 0.0
    i11, i12, i21, i22 = e * det, -b * det, -d * det, a * det
    b1, b2 = -i11 * c - i12 * f, -i21 * c - i22 * f
    h_src, w_src = img.shape[:2]
    gx, gy = np.meshgrid(np.arange(out_w, dtype=np.float64), np.arange(out_h, dtype=np.float64))
    X = i11 * gx + i12 * gy + b1
    Y = i21 * gx + i22 * gy + b2
    x0 = np.floor(X).astype(np.int64)
    y0 = np.floor(Y).astype(np.int64)
    fx = (X - x0)[:, :, None]
    fy = (Y - y0)[:, :, None]

    def px(yy, xx):
        inside = (xx >= 0) & (xx < w_src) & (yy >= 0) & (yy < h_src)
        vals = np.zeros(yy.shape + (img.shape[2],), dtype=np.float64)
        vals[inside] = img[yy[inside], xx[inside]]
        return vals

    top = (1 - fx) * px(y0, x0) + fx * px(y0, x0 + 1)
    bottom = (1 - fx) * px(y0 + 1, x0) + fx * px(y0 + 1, x0 + 1)
    return np.clip(np.floor((1 - fy) * top + fy * bottom + 0.5), 0, 255).astype(np.uint8)


def sface_input(aligned_bgr):
    """[1, 3, 112, 112] float32 NCHW, channels R, G, B, raw 0..255: cv::dnn::blobFromImage(aligned, 1, (112, 112),
    (0, 0, 0), swapRB=true, crop=false) (face_recognize.cpp:58)."""
    return aligned_bgr[:, :, ::-1].transpose(2, 0, 1)[None].astype(np.float32)


class SFace:
    """SFace (MobileFaceNet): input "data" [1,3,112,112]; output "fc1" [1,128], the feature (not normalised)."""

    def __init__(self, path, threads=2):
        self.session = new_session(path, threads)

    def run(self, tensor):
        return self.session.run([SFACE_OUTPUT], {SFACE_INPUT: tensor})[0][0].astype(np.float64)


def sface_embed(model, bgr, face):
    """(feature, aligned crop) of one detected face: transform, warp, blob, network."""
    aligned = warp_affine_cv(bgr, sface_transform(face["kps"]), SFACE_SIZE, SFACE_SIZE)
    return model.run(sface_input(aligned)), aligned


def sface_cosine(f1, f2):
    """FaceRecognizerSF::match with FR_COSINE (face_recognize.cpp:62-69): both features L2-normalised
    (cv::normalize), then their dot product."""
    a = np.asarray(f1, dtype=np.float64)
    b = np.asarray(f2, dtype=np.float64)
    return float(np.dot(a / np.linalg.norm(a), b / np.linalg.norm(b)))


def face_match(pipe, bgr_a, bgr_b, threshold=SFACE_COSINE_THRESHOLD):
    """The Java FaceMatcher.compare: YuNet on each image, exactly one face each (faceCount as count_faces), then the
    cosine of the two embeddings. Returns (outcome, similarity): NO_FACE_A / MULTIPLE_FACES_A / NO_FACE_B /
    MULTIPLE_FACES_B (similarity None), else MATCH when similarity >= threshold, otherwise NO_MATCH."""
    feats = []
    for tag, bgr in (("A", bgr_a), ("B", bgr_b)):
        n, primary = count_faces(pipe.yunet.detect(bgr))
        if n == 0:
            return "NO_FACE_" + tag, None
        if n > 1:
            return "MULTIPLE_FACES_" + tag, None
        feats.append(sface_embed(pipe.sface, bgr, primary)[0])
    sim = sface_cosine(feats[0], feats[1])
    return ("MATCH" if sim >= threshold else "NO_MATCH"), sim


# ---------------------------------------------------------------------------------------------
# Quality and replay cues (primary face)
# ---------------------------------------------------------------------------------------------

def box_matrix(box, size):
    """Axis-aligned (unrotated) crop of the float face box [x, y, w, h] to size x size:
    M = [[w/N, 0, x], [0, h/N, y]] (continuous coordinates, so dst [0, N] spans the box)."""
    x, y, w, h = box
    return [[w / size, 0.0, x], [0.0, h / size, y]]


def to_gray(crop):
    """BT.601 luma of a bilinear-sampled float32 BGR crop, per output pixel AFTER sampling:
    gray = 0.299 R + 0.587 G + 0.114 B, in float64 from the float32 channel values, stored as float32."""
    c = crop.astype(np.float64)
    return (0.299 * c[:, :, 2] + 0.587 * c[:, :, 1] + 0.114 * c[:, :, 0]).astype(np.float32)


def laplacian_variance(gray):
    """Population variance of the 4-neighbour Laplacian [[0,1,0],[1,-4,1],[0,1,0]] over the interior
    (N-2) x (N-2) pixels: L = up + down + left + right - 4 centre, in float64."""
    g = gray.astype(np.float64)
    lap = g[:-2, 1:-1] + g[2:, 1:-1] + g[1:-1, :-2] + g[1:-1, 2:] - 4.0 * g[1:-1, 1:-1]
    return float(np.var(lap))


def quality(bgr, box):
    """Quality metrics and issues for the primary face.

    crop      = sample_affine(frame, box_matrix(box, 112), 112, 112); gray = to_gray(crop)
    lumaMean  = mean(gray)                  lapVar = laplacian_variance(gray)
    areaFrac  = w h / (W H)                 faceWidthPx = w
    centreDx  = |x + w/2 - W/2| / W         centreDy = |y + h/2 - H/2| / H
    cutOff    = x < 0 or y < 0 or x + w > W or y + h > H
    Issues (in this order): TOO_DARK lumaMean < 40; TOO_BRIGHT lumaMean > 220 or clipped_fraction(frame, box) > 0.30
    (the replay cues' glare: same crop and rule); BLURRY lapVar < Q_BLUR_LAP_VAR;
    FACE_TOO_SMALL areaFrac < 0.04 or w < 80; FACE_TOO_LARGE areaFrac > 0.60;
    FACE_OFF_CENTER centreDx > 0.25 or centreDy > 0.25; FACE_CUT_OFF cutOff.
    """
    H, W = bgr.shape[:2]
    x, y, w, h = box
    g = to_gray(sample_affine(bgr, box_matrix(box, QUALITY_SIZE), QUALITY_SIZE, QUALITY_SIZE))
    m = {"lumaMean": float(g.astype(np.float64).mean()), "lapVar": laplacian_variance(g),
         "areaFrac": float((w * h) / (W * H)), "faceWidthPx": float(w),
         "centreDx": float(abs(x + w / 2 - W / 2) / W), "centreDy": float(abs(y + h / 2 - H / 2) / H),
         "cutOff": bool(x < 0 or y < 0 or x + w > W or y + h > H)}
    issues = []
    if m["lumaMean"] < Q_TOO_DARK_LUMA:
        issues.append("TOO_DARK")
    if m["lumaMean"] > Q_TOO_BRIGHT_LUMA or clipped_fraction(bgr, box) > Q_MAX_CLIPPED_FRACTION:
        issues.append("TOO_BRIGHT")
    if m["lapVar"] < Q_BLUR_LAP_VAR:
        issues.append("BLURRY")
    if m["areaFrac"] < Q_MIN_AREA_FRAC or w < Q_MIN_FACE_WIDTH_PX:
        issues.append("FACE_TOO_SMALL")
    if m["areaFrac"] > Q_MAX_AREA_FRAC:
        issues.append("FACE_TOO_LARGE")
    if m["centreDx"] > Q_MAX_CENTRE_OFFSET or m["centreDy"] > Q_MAX_CENTRE_OFFSET:
        issues.append("FACE_OFF_CENTER")
    if m["cutOff"]:
        issues.append("FACE_CUT_OFF")
    m["issues"] = issues
    return m


def hann(n):
    """Symmetric Hann window (numpy.hanning): w[k] = 0.5 - 0.5 cos(2 pi k / (n - 1))."""
    return np.hanning(n)


def replay_cues(bgr, box):
    """Replay/print cues for the audit trail (not fused into the score).

    moire: g = to_gray(64 x 64 box crop) in float64, minus its mean, times outer(hann(64), hann(64));
           F = |fftshift(fft2(g))| (DC at index [32, 32]); S = F over frequencies with
           8 <= sqrt(u^2 + v^2) <= 31 (u = col - 32, v = row - 32);
           moire = log10((max(S) + 1e-6) / (median(S) + 1e-6))   (median of an even count = mean of the two middle values)
    glare: fraction of pixels of the 112 x 112 float BGR box crop whose R, G and B are all >= 250.
    """
    g = to_gray(sample_affine(bgr, box_matrix(box, MOIRE_SIZE), MOIRE_SIZE, MOIRE_SIZE)).astype(np.float64)
    g -= g.mean()
    win = hann(MOIRE_SIZE)
    g *= np.outer(win, win)
    mag = np.abs(np.fft.fftshift(np.fft.fft2(g)))
    v, u = np.mgrid[0:MOIRE_SIZE, 0:MOIRE_SIZE] - MOIRE_SIZE // 2
    r = np.sqrt(u.astype(np.float64) ** 2 + v.astype(np.float64) ** 2)
    sel = mag[(r >= MOIRE_R_MIN) & (r <= MOIRE_R_MAX)]
    moire = math.log10((float(sel.max()) + MOIRE_EPS) / (float(np.median(sel)) + MOIRE_EPS))
    return {"moire": moire, "glare": clipped_fraction(bgr, box)}


def clipped_fraction(bgr, box):
    """Fraction of pixels of the 112 x 112 float BGR box crop whose R, G and B are all >= 250 (the replay cues'
    glare; quality() also uses it for TOO_BRIGHT)."""
    crop = sample_affine(bgr, box_matrix(box, QUALITY_SIZE), QUALITY_SIZE, QUALITY_SIZE)
    return float(np.mean(np.all(crop >= GLARE_LEVEL, axis=2)))


# ---------------------------------------------------------------------------------------------
# Whole pipeline
# ---------------------------------------------------------------------------------------------

def _ms(t0):
    return (time.perf_counter() - t0) * 1000.0


class Pipeline:
    def __init__(self, models_dir=MODELS, threads=2):
        models_dir = Path(models_dir)
        self.yunet = YuNet(models_dir / YUNET_FILE, threads)
        self.mesh = FaceMesh(models_dir / MESH_FILE, threads)
        self.pad_v2 = MiniFasNet(models_dir / PAD_V2_FILE, PAD_V2_SCALE, threads)
        self.pad_v1se = MiniFasNet(models_dir / PAD_V1SE_FILE, PAD_V1SE_SCALE, threads)
        # Face binding only (not part of analyze()); optional so folders with the liveness models alone still load.
        self.sface = SFace(models_dir / SFACE_FILE, threads) if (models_dir / SFACE_FILE).exists() else None

    def analyze(self, bgr):
        """Full reference analysis of one BGR frame. Mesh, pose and PAD run on the primary face
        even when faceCount > 1 (the production analyzer skips them then; see plan D8 step 3) so
        the fixtures cover every stage. Missing values are None (NaN in Java)."""
        t_all = time.perf_counter()
        h, w = bgr.shape[:2]
        res = {"image": {"width": w, "height": h}, "timingsMs": {}, "tensorStats": {}}
        dbg = {}

        t0 = time.perf_counter()
        faces = self.yunet.detect(bgr, dbg)
        res["timingsMs"]["detect"] = _ms(t0)
        res["yunet"] = dbg["yunet"]
        res["tensorStats"]["yunet"] = dbg["yunetTensor"]
        res["detections"] = faces
        face_count, primary = count_faces(faces)
        res["faceCount"] = face_count
        res["primary"] = primary
        res["faceBox"] = primary["box"] if primary else None
        for key in ("quality", "replay", "meshRoi", "meshFlag", "meshPoints", "landmarks", "earLeft", "earRight",
                    "yawDeg", "pitchDeg", "pitchRaw", "rollDeg", "pad", "livenessScore"):
            res[key] = None
        if primary is None:
            res["timingsMs"]["total"] = _ms(t_all)
            return res

        t0 = time.perf_counter()
        res["quality"] = quality(bgr, primary["box"])
        res["timingsMs"]["quality"] = _ms(t0)
        t0 = time.perf_counter()
        res["replay"] = replay_cues(bgr, primary["box"])
        res["timingsMs"]["cues"] = _ms(t0)

        t0 = time.perf_counter()
        roi = mesh_roi(primary)
        mesh_tensor = mesh_input(bgr, roi)
        raw, flag = self.mesh.run(mesh_tensor)
        res["timingsMs"]["mesh"] = _ms(t0)
        res["tensorStats"]["mesh"] = tensor_stats(mesh_tensor)
        res["meshRoi"] = roi
        res["meshFlag"] = flag
        if flag >= MESH_FLAG_THRESHOLD:
            pts = back_project(raw, roi)
            res["meshPoints"] = pts.tolist()
            lms = named_points(pts)
            res["landmarks"] = lms
            res["earLeft"] = eye_aspect_ratio(lms["leftEye"])
            res["earRight"] = eye_aspect_ratio(lms["rightEye"])
            t0 = time.perf_counter()
            res["yawDeg"], res["pitchRaw"], res["rollDeg"] = head_pose(pts)
            res["pitchDeg"] = res["pitchRaw"] - PITCH_BIAS_DEG
            res["timingsMs"]["pose"] = _ms(t0)

        ibox = pad_box(primary["box"])
        pad = {"intBox": ibox}
        probs = []
        for key, model in (("v2", self.pad_v2), ("v1se", self.pad_v1se)):
            t0 = time.perf_counter()
            crop_box = get_new_box(w, h, ibox, model.scale)
            tensor = pad_input(bgr, crop_box)
            logits, sm = model.run(tensor)
            res["timingsMs"]["pad_" + key] = _ms(t0)
            res["tensorStats"]["pad_" + key] = tensor_stats(tensor)
            pad[key] = {"scale": model.scale, "cropBox": list(crop_box), "logits": logits.tolist(),
                        "softmax": sm.tolist()}
            probs.append(sm[PAD_REAL_CLASS])
        res["pad"] = pad
        res["livenessScore"] = float(sum(probs) / 2.0)
        res["timingsMs"]["total"] = _ms(t_all)
        return res


# ---------------------------------------------------------------------------------------------
# Session moire cap (stateful: the Java analyzer is one per session)
# ---------------------------------------------------------------------------------------------

def session_cfg(cap=MOIRE_CAP_THRESHOLD, cap_score=MOIRE_CAP_SCORE, window=MOIRE_WINDOW,
                min_frames=MOIRE_MIN_FRAMES, veto=MOIRE_VETO_THRESHOLD):
    return {"cap": cap, "capScore": cap_score, "window": window, "minFrames": min_frames, "veto": veto}


def session_scores(frame_results, cfg=None):
    """Applies the session moire cap to a session's frame results, in order. Returns one dict per frame:
    {"livenessScore", "moireMed", "moireCap"}.

    A phone or monitor held close can fool MiniFASNet, but its pixel grid keeps the face crop's moire high frame
    after frame. Per frame, with buf = the last cfg["window"] moire values:
      * faceCount != 1: buf is cleared (no face, or a different face may have been presented); the score stays
        None, moireMed None, moireCap False.
      * faceCount == 1: the frame's replay moire is appended to buf, also when the frame has no score (pose-gated
        or no mesh): a screen is a screen at any angle. moireMed = median(buf) (even count: mean of the two middle
        values).
      * When len(buf) >= cfg["minFrames"]:
          - if veto is set and moireMed >= veto: score = 0, moireCap True;
          - elif cap is set and moireMed >= cap: score = min(score, capScore), moireCap True.
        A frame without a score keeps None (moireCap still reports the condition).
    Java compares in float32 (the moire is a float), so the thresholds are compared after rounding to float32.
    """
    cfg = cfg or session_cfg()
    f32 = lambda v: float(np.float32(v))  # noqa: E731
    buf, out = [], []
    for r in frame_results:
        score = r.get("livenessScore")
        if r.get("faceCount") != 1:
            buf = []
            out.append({"livenessScore": score, "moireMed": None, "moireCap": False})
            continue
        moire = (r.get("replay") or {}).get("moire")
        if moire is not None:
            buf = (buf + [f32(moire)])[-cfg["window"]:]
        med = f32(np.median(buf)) if buf else None
        capped = False
        if len(buf) >= cfg["minFrames"]:
            if cfg["veto"] is not None and med >= f32(cfg["veto"]):
                capped, score = True, (None if score is None else 0.0)
            elif cfg["cap"] is not None and med >= f32(cfg["cap"]):
                capped, score = True, (None if score is None else min(score, f32(cfg["capScore"])))
        out.append({"livenessScore": score, "moireMed": med, "moireCap": capped})
    return out


# The synthetic sequence the Java MoireCapTest replays: (faceCount, livenessScore, moire).
SESSION_SELF_TEST = [
    (1, 0.95, 1.20), (1, 0.95, 1.50), (1, 0.95, 1.50), (1, 0.40, 1.50), (1, None, 1.60),  # cap from frame 3
    (0, None, None), (1, 0.95, 1.50), (1, 0.95, 1.50),                                     # face lost: reset
    (2, None, None), (1, 0.95, 1.39), (1, 0.95, 1.39), (1, 0.95, 1.39),                     # two faces: reset
    (1, 0.95, 1.40), (1, 0.95, 1.41), (1, 0.95, 1.41), (1, 0.95, 1.41),                     # median 1.39 -> 1.40
]
SESSION_EXPECTED = {
    "default": [0.95, 0.95, 0.60, 0.40, None, None, 0.95, 0.95, None, 0.95, 0.95, 0.95, 0.95, 0.95, 0.95, 0.60],
    "veto1.45": [0.95, 0.95, 0.0, 0.0, None, None, 0.95, 0.95, None, 0.95, 0.95, 0.95, 0.95, 0.95, 0.95, 0.60],
    "off": [0.95, 0.95, 0.95, 0.40, None, None, 0.95, 0.95, None, 0.95, 0.95, 0.95, 0.95, 0.95, 0.95, 0.95],
}


def session_self_test():
    """Runs SESSION_SELF_TEST under three configs and checks SESSION_EXPECTED; prints the trace."""
    frames = [{"faceCount": n, "livenessScore": s, "replay": None if m is None else {"moire": m}}
              for n, s, m in SESSION_SELF_TEST]
    configs = {"default": session_cfg(), "veto1.45": session_cfg(veto=1.45), "off": session_cfg(cap=None)}
    ok = True
    for name, cfg in configs.items():
        got = session_scores(frames, cfg)
        for i, (g, want) in enumerate(zip(got, SESSION_EXPECTED[name])):
            s = g["livenessScore"]
            match = (s is None and want is None) or (s is not None and want is not None and abs(s - want) < 1e-6)
            ok &= match
            print(f"{name:9s} frame {i:2d} faces {SESSION_SELF_TEST[i][0]} moire {SESSION_SELF_TEST[i][2]} -> "
                  f"med {g['moireMed']} cap {g['moireCap']} score {s} {'ok' if match else 'MISMATCH, want ' + str(want)}")
    print("session self-test", "PASSED" if ok else "FAILED")
    return 0 if ok else 1


def to_json(res):
    """JSON text with NaN mapped to null (JSON has no NaN)."""
    def clean(v):
        if isinstance(v, float):
            return None if math.isnan(v) else v
        if isinstance(v, dict):
            return {k: clean(x) for k, x in v.items()}
        if isinstance(v, (list, tuple)):
            return [clean(x) for x in v]
        if isinstance(v, np.generic):
            return clean(v.item())
        return v
    return json.dumps(clean(res), indent=1)


def summary(res):
    f = lambda v, fmt="{:.3f}": "null" if v is None else fmt.format(v)  # noqa: E731
    return (f"faceCount={res['faceCount']} livenessScore={f(res['livenessScore'], '{:.4f}')} "
            f"yaw={f(res['yawDeg'], '{:.2f}')} pitch={f(res['pitchDeg'], '{:.2f}')} roll={f(res['rollDeg'], '{:.2f}')} "
            f"earL={f(res['earLeft'])} earR={f(res['earRight'])} meshFlag={f(res['meshFlag'], '{:.4f}')} "
            f"ms={ {k: round(v, 1) for k, v in res['timingsMs'].items()} }")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("image", nargs="?")
    ap.add_argument("--flip", action="store_true", help="mirror the decoded pixels horizontally first")
    ap.add_argument("--json", help="write the full result to this file")
    ap.add_argument("--session", action="store_true", help="run the session moire cap self-test and exit")
    args = ap.parse_args()
    if args.session:
        return session_self_test()
    if not args.image:
        ap.error("an image is required (or --session)")
    bgr = imread_bgr(args.image)
    if args.flip:
        bgr = np.ascontiguousarray(bgr[:, ::-1])
    res = Pipeline().analyze(bgr)
    print(summary(res))
    if args.json:
        Path(args.json).write_text(to_json(res), encoding="utf-8")


if __name__ == "__main__":
    sys.exit(main())
