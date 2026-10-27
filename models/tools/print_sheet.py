"""Print sheets for the print-attack takes across identities (F3, print_multi; development only):

    python -I models/tools/print_sheet.py --photos DIR --metrics eval-data/photo-set/photos.csv --out SHEET.pdf
                                          [--people 12] [--per-page 1|6] [--face-cm 15] [--exclude p13,...]

The photos are consented personal data: --out must be OUTSIDE the repository, and the printed sheets are kept and
destroyed like the photos. One photo per person (person_id in DIR/labels.csv): among that person's photos with exactly
one face, landmarks, |yaw| <= 15 and |pitch| <= 20 degrees and no BLURRY / TOO_DARK / TOO_BRIGHT / FACE_CUT_OFF, the one
with the largest inter-eye distance (metrics from photo_set_analysis.py). The people whose best photo is largest come
first. Each is cropped around the YuNet face box (1.6 x its width, 1.7 x its height, the face a little above the
centre) and placed so that the face box is --face-cm tall:
  * --per-page 1: one per A4 page, --face-cm 15 (about life size);
  * --per-page 6: 2 x 3 per A4 page, the face about 5 cm (--face-cm is then capped to fit).
Each cut-out has a thin cut line and a caption (person_id and photo index, never a name); every page has a 10 cm scale
bar: print at 100% (actual size), not "fit to page". The PDF is written directly (no library): JPEG images (DCTDecode)
placed at their physical size, upsampled to at least 150 dpi.
"""
import argparse
import csv
import sys
from pathlib import Path

import cv2

import reference_pipeline as rp

PT_PER_CM = 72 / 2.54
A4_W, A4_H = 21.0, 29.7
MARGIN = 1.0
CROP_W, CROP_H, ABOVE = 1.6, 1.7, 0.45  # of the face box; ABOVE = share of the extra height above the box
BAD = {"BLURRY", "TOO_DARK", "TOO_BRIGHT", "FACE_CUT_OFF"}
MIN_DPI = 150


def choose(photos, metrics, people, exclude):
    labels = {}
    with open(photos / "labels.csv", newline="", encoding="utf-8-sig") as fh:
        for r in csv.DictReader(fh):
            if r.get("person_id"):
                labels[r["file"]] = r["person_id"].strip()
    best = {}
    with open(metrics, newline="", encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            p = labels.get(r["file"])
            if not p or p in exclude or r["faces"] != "1" or r["mesh"] != "True" or not r["yaw"]:
                continue
            if abs(float(r["yaw"])) > 15 or abs(float(r["pitch"])) > 20 or BAD & set(r["issues"].split()):
                continue
            eye = float(r["interEyePx"])
            if p not in best or eye > best[p][0]:
                best[p] = (eye, r["file"], int(r["index"]))
    ranked = sorted(best.items(), key=lambda kv: -kv[1][0])[:people]
    return [(p, f, idx) for p, (_, f, idx) in ranked]


def crop(bgr, box):
    H, W = bgr.shape[:2]
    x, y, w, h = box
    cw, ch = CROP_W * w, CROP_H * h
    x0 = x + w / 2 - cw / 2
    y0 = y - (ch - h) * ABOVE
    x0 = min(max(0.0, x0), max(0.0, W - cw))
    y0 = min(max(0.0, y0), max(0.0, H - ch))
    x1, y1 = min(W, x0 + cw), min(H, y0 + ch)
    return bgr[int(y0):int(y1), int(x0):int(x1)], h / ch  # the face box's share of the crop height


class Pdf:
    def __init__(self):
        self.objects = []

    def add(self, body):
        self.objects.append(body)
        return len(self.objects)

    def write(self, path, pages):
        out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
        offsets = []
        for i, body in enumerate(self.objects, 1):
            offsets.append(len(out))
            out += f"{i} 0 obj\n".encode() + body + b"\nendobj\n"
        xref = len(out)
        out += f"xref\n0 {len(self.objects) + 1}\n0000000000 65535 f \n".encode()
        for o in offsets:
            out += f"{o:010d} 00000 n \n".encode()
        out += f"trailer\n<< /Size {len(self.objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
        Path(path).write_bytes(bytes(out))


def stream(data, extra=b""):
    return b"<< /Length " + str(len(data)).encode() + extra + b" >>\nstream\n" + data + b"\nendstream"


def text(s):
    return "(" + s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")"


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--photos", required=True)
    ap.add_argument("--metrics", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--people", type=int, default=12)
    ap.add_argument("--per-page", type=int, choices=(1, 6), default=1)
    ap.add_argument("--face-cm", type=float, default=15.0)
    ap.add_argument("--exclude", default="")
    args = ap.parse_args()
    out = Path(args.out).resolve()
    repo = rp.MODELS.parent.resolve()
    if repo in out.parents:
        sys.exit("--out must be outside the repository")
    photos = Path(args.photos)
    chosen = choose(photos, Path(args.metrics), args.people, {e for e in args.exclude.split(",") if e})
    if not chosen:
        sys.exit("no photo qualifies")
    if args.per_page == 1:
        cols, rows = 1, 1
    else:
        cols, rows = 2, 3
    cell_w = (A4_W - 2 * MARGIN) / cols
    cell_h = (A4_H - 2 * MARGIN - 1.5) / rows  # 1.5 cm for the header and the scale bar
    yunet = rp.YuNet(rp.MODELS / rp.YUNET_FILE)
    pdf = Pdf()
    pdf.add(b"<< /Type /Catalog /Pages 2 0 R >>")
    pdf.add(b"")  # the page tree, filled in at the end
    font = pdf.add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    page_ids, face_cm_used = [], []
    for start in range(0, len(chosen), cols * rows):
        ops, xobjects = [], []
        ops.append(f"BT /F1 9 Tf {MARGIN * PT_PER_CM:.2f} {(A4_H - 0.8) * PT_PER_CM:.2f} Td "
                   f"{text('Print at 100% (actual size), not fit to page. The bar below must measure 10 cm.')} Tj ET")
        bar_y = (A4_H - 1.4) * PT_PER_CM
        ops.append(f"0 G 1 w {MARGIN * PT_PER_CM:.2f} {bar_y:.2f} m {(MARGIN + 10) * PT_PER_CM:.2f} {bar_y:.2f} l S")
        for k, (person, file, index) in enumerate(chosen[start:start + cols * rows]):
            bgr = rp.imread_bgr(str(photos / file))
            n, primary = rp.count_faces(yunet.detect(bgr))
            img, face_share = crop(bgr, primary["box"])
            ch_px, cw_px = img.shape[:2]
            # Face box height in cm, capped so the crop fits the cell (with 0.6 cm for the caption).
            face_cm = min(args.face_cm, (cell_h - 0.6) * face_share, (cell_w - 0.2) * face_share * ch_px / cw_px)
            face_cm_used.append(face_cm)
            h_cm = face_cm / face_share
            w_cm = h_cm * cw_px / ch_px
            min_px = int(h_cm / 2.54 * MIN_DPI)
            if ch_px < min_px:
                img = cv2.resize(img, (int(cw_px * min_px / ch_px), min_px), interpolation=cv2.INTER_CUBIC)
            ok, jpg = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 92])
            im = pdf.add(stream(jpg.tobytes(), f" /Type /XObject /Subtype /Image /Width {img.shape[1]} /Height "
                                f"{img.shape[0]} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode "
                                f"/Interpolate true".encode()))
            name = f"Im{k + 1}"
            xobjects.append(f"/{name} {im} 0 R")
            c, r = k % cols, k // cols
            cx = MARGIN + c * cell_w + (cell_w - w_cm) / 2
            top = A4_H - MARGIN - 1.5 - r * cell_h
            y = top - h_cm
            X, Y, Wp, Hp = (v * PT_PER_CM for v in (cx, y, w_cm, h_cm))
            ops.append(f"q {Wp:.2f} 0 0 {Hp:.2f} {X:.2f} {Y:.2f} cm /{name} Do Q")
            ops.append(f"0.6 G 0.3 w {X:.2f} {Y:.2f} {Wp:.2f} {Hp:.2f} re S")
            ops.append(f"BT /F1 8 Tf {X:.2f} {Y - 10:.2f} Td {text(f'{person}   photo #{index}   face {face_cm:.1f} cm')} Tj ET")
        content = pdf.add(stream("\n".join(ops).encode()))
        page_ids.append(pdf.add(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {A4_W * PT_PER_CM:.3f} "
                                f"{A4_H * PT_PER_CM:.3f}] /Resources << /Font << /F1 {font} 0 R >> /XObject << "
                                f"{' '.join(xobjects)} >> >> /Contents {content} 0 R >>".encode()))
    pdf.objects[1] = f"<< /Type /Pages /Kids [{' '.join(f'{p} 0 R' for p in page_ids)}] /Count {len(page_ids)} >>".encode()
    out.parent.mkdir(parents=True, exist_ok=True)
    pdf.write(out, page_ids)
    print(f"wrote {out}: {len(chosen)} people on {len(page_ids)} pages, face box {min(face_cm_used):.1f}-"
          f"{max(face_cm_used):.1f} cm; people: {', '.join(p for p, _, _ in chosen)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
