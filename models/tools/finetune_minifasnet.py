"""F2b prep: leave-one-subject-out fine-tuning of the Silent-Face MiniFASNet models on CPU (development only).

Nothing here is adopted: no ONNX file is written to models/, and no bundle is packed or signed. The real run comes
after the F9 teammate data (docs/part2-f2b-finetune-prep.md).

    python -I models/tools/finetune_minifasnet.py --data eval-data --out eval-data/f2b/<run>
        [--weights-dir models/tools/.cache] [--model v2|v1se|both] [--epochs 3] [--batch 32] [--lr 1e-4]
        [--frame-step 3] [--max-frames 40] [--threads 2] [--head-only] [--folds s01,p05] [--max-folds N]
        [--save-weights] [--dry-run]
    python -I models/tools/finetune_minifasnet.py --self-test

Data: the eval-data takes as DatasetRecorder writes them (bona_fide/<subject>/<take>/ and
attack/<species>/<subject>/<take>/, frame_NNNNN.jpg). Each kept frame goes through YuNet; a frame must hold exactly
one face, counted as the analyzer counts faces. Then the model's Silent-Face crop: get_new_box at its scale, 80 x 80,
BGR, raw 0..255, as the engine does (reference_pipeline).

Labels follow Silent-Face's three-class head: bona fide 1 (the engine reads softmax[1]), prints 0, screens 2.

Folds: leave one subject out. A subject is the person whose face the take shows:
- bona fide sNN;
- for an attack, its subject code: the participant's own photos are theirs, and a photographed person is their
  pXX, qXX or uXX.
Each fold fine-tunes the released weights on every other subject and evaluates on the held-out one. The released
weights are evaluated on the same frames for comparison. A fold is skipped, and reported, when its training set lacks
bona fide or attack frames, or when nothing is held out.

Metrics: per take, the median of its frames' softmax[1], read against the engine's passive thresholds (0.85 pass,
0.30 reject). Bona fide takes: BPCER-pass (below 0.85: no passive pass) and the rejected share (below 0.30). Attack
takes: the passed share (0.85 or above: passes passively) and the not-rejected share (0.30 or above). Also the mean
score per class. Reported per fold and pooled over the folds.

Privacy: crops are face images, kept in memory only. --out must be inside an eval-data folder (gitignored). The report
has aggregates only: counts, rates and means, no images and no per-frame or per-take values. Fine-tuned weights are
written only with --save-weights, also under --out.
"""
import argparse
import math
import random
import statistics
import sys
import time
from datetime import date
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
sys.path.insert(0, str(TOOLS / "third_party" / "silent_face"))
import reference_pipeline as rp  # noqa: E402
from MiniFASNet import MiniFASNetV1SE, MiniFASNetV2  # noqa: E402  (vendored, unmodified)

MODELS = {
    "v2": dict(build=MiniFASNetV2, weights="2.7_80x80_MiniFASNetV2.pth", scale=rp.PAD_V2_SCALE),
    "v1se": dict(build=MiniFASNetV1SE, weights="4_0_0_80x80_MiniFASNetV1SE.pth", scale=rp.PAD_V1SE_SCALE),
}
REAL, PRINT, SCREEN = 1, 0, 2
PASSIVE, REJECT = 0.85, 0.30
FRAME_NAME = "frame_"


# ---------------------------------------------------------------------------------------------------------------
# Data

class Take:
    def __init__(self, species, subject, take, directory):
        self.species = species
        self.subject = subject
        self.take = take
        self.dir = directory
        self.label = label_of(species)

    @property
    def key(self):
        return f"{self.species}/{self.subject}/{self.take}"

    def frames(self, step, limit):
        files = sorted(p for p in self.dir.iterdir()
                       if p.name.startswith(FRAME_NAME) and p.suffix.lower() in (".jpg", ".png"))
        return files[::max(1, step)][:limit]


def label_of(species):
    """Bona fide 1; prints 0; screens 2; anything else None (not used)."""
    if species == "bona_fide":
        return REAL
    if species.startswith("print"):
        return PRINT
    if species.startswith("screen"):
        return SCREEN
    return None


def scan(data):
    """Every take with frames, bona fide first; species without a label are left out."""
    takes = []
    roots = [("bona_fide", data / "bona_fide")]
    attack = data / "attack"
    if attack.is_dir():
        roots += [(s.name, s) for s in sorted(attack.iterdir()) if s.is_dir()]
    for species, root in roots:
        if not root.is_dir() or label_of(species) is None:
            continue
        for subject in sorted(p for p in root.iterdir() if p.is_dir()):
            for take in sorted((p for p in subject.iterdir() if p.is_dir()), key=lambda p: (len(p.name), p.name)):
                if any(f.name.startswith(FRAME_NAME) for f in take.iterdir()):
                    takes.append(Take(species, subject.name, take.name, take))
    return takes


def crops(takes, scale, step, limit, yunet):
    """{take key: float32 [n, 3, 80, 80]} for frames with exactly one face; and the count of frames without."""
    out, skipped = {}, 0
    for t in takes:
        kept = []
        for f in t.frames(step, limit):
            bgr = rp.imread_bgr(f)
            n, primary = rp.count_faces(yunet.detect(bgr))
            if n != 1:
                skipped += 1
                continue
            h, w = bgr.shape[:2]
            box = rp.get_new_box(w, h, rp.pad_box(primary["box"]), scale)
            kept.append(rp.pad_input(bgr, box)[0])
        if kept:
            out[t.key] = np.stack(kept).astype(np.float32)
    return out, skipped


def folds(takes, only=None):
    """Leave one subject out: [(held-out subject, train takes, test takes, reason to skip or None)]."""
    subjects = sorted({t.subject for t in takes})
    if only:
        subjects = [s for s in subjects if s in only]
    result = []
    for held in subjects:
        train = [t for t in takes if t.subject != held]
        test = [t for t in takes if t.subject == held]
        reason = None
        if not any(t.label == REAL for t in train):
            reason = "no bona fide takes left to train on"
        elif not any(t.label != REAL for t in train):
            reason = "no attack takes left to train on"
        elif not test:
            reason = "nothing held out"
        result.append((held, train, test, reason))
    return result


# ---------------------------------------------------------------------------------------------------------------
# Model

def load_model(spec, weights_dir):
    """The released weights, as export_minifasnet loads them; random weights when weights_dir is None (self-test)."""
    model = spec["build"](embedding_size=128, conv6_kernel=(5, 5), num_classes=3)
    if weights_dir is None:
        return model.eval()
    path = Path(weights_dir) / spec["weights"]
    if not path.is_file():
        raise SystemExit(f"{path} is missing: run models/tools/fetch_models.py, or pass --weights-dir")
    state = torch.load(path, map_location="cpu", weights_only=True)
    state = {(k[7:] if k.startswith("module.") else k): v for k, v in state.items()}
    model.load_state_dict(state, strict=True)
    return model.eval()


def head(model):
    """The classifier head: the embedding layer, its batch norm and the class layer."""
    return [model.linear, model.bn, model.prob]


def augment(x, gen):
    """Horizontal flip and a brightness/contrast jitter, on raw 0..255 BGR."""
    flip = torch.rand(x.shape[0], generator=gen) < 0.5
    x = torch.where(flip[:, None, None, None], x.flip(3), x)
    c = 0.8 + 0.4 * torch.rand(x.shape[0], 1, 1, 1, generator=gen)
    b = -20.0 + 40.0 * torch.rand(x.shape[0], 1, 1, 1, generator=gen)
    return ((x - 127.5) * c + 127.5 + b).clamp(0.0, 255.0)


def train(model, x, y, epochs, batch, lr, head_only, seed):
    """Fine-tunes in place with cross-entropy, class-balanced sampling and augmentation; returns the model (eval mode)."""
    gen = torch.Generator().manual_seed(seed)
    for p in model.parameters():
        p.requires_grad = not head_only
    params = [p for m in head(model) for p in m.parameters()] if head_only else list(model.parameters())
    for p in params:
        p.requires_grad = True
    opt = torch.optim.Adam(params, lr=lr, weight_decay=1e-4)
    counts = torch.bincount(y, minlength=3).float()
    weights = (1.0 / counts.clamp(min=1))[y]
    n = len(y)
    for _ in range(epochs):
        if head_only:
            model.eval()  # the backbone's batch norms keep their statistics
            for m in head(model):
                m.train()
            model.drop.train()
        else:
            model.train()
        order = torch.multinomial(weights, n, replacement=True, generator=gen)
        for i in range(0, n - 1, batch):
            idx = order[i:i + batch]
            if len(idx) < 2:  # batch norm needs two samples in training mode
                continue
            loss = F.cross_entropy(model(augment(x[idx], gen)), y[idx])
            opt.zero_grad()
            loss.backward()
            opt.step()
    return model.eval()


@torch.no_grad()
def scores(model, x, batch=64):
    """softmax[1] (real) per frame."""
    model.eval()
    out = [torch.softmax(model(x[i:i + batch]), dim=1)[:, REAL] for i in range(0, len(x), batch)]
    return torch.cat(out).numpy() if out else np.zeros(0)


# ---------------------------------------------------------------------------------------------------------------
# Metrics

class Tally:
    """Per-take medians against the engine's thresholds, accumulated."""

    def __init__(self):
        self.bona, self.attack = [], []

    def add(self, label, take_median):
        (self.bona if label == REAL else self.attack).append(take_median)

    def extend(self, other):
        self.bona += other.bona
        self.attack += other.attack

    def row(self):
        def share(values, test):
            return "-" if not values else f"{sum(1 for v in values if test(v))}/{len(values)}"

        def mean(values):
            return "-" if not values else f"{statistics.fmean(values):.3f}"

        return [share(self.bona, lambda v: v < PASSIVE), share(self.bona, lambda v: v < REJECT), mean(self.bona),
                share(self.attack, lambda v: v >= PASSIVE), share(self.attack, lambda v: v >= REJECT),
                mean(self.attack)]


def evaluate(model, takes, cache):
    tally = Tally()
    for t in takes:
        x = cache.get(t.key)
        if x is None:
            continue
        tally.add(t.label, float(np.median(scores(model, torch.from_numpy(x)))))
    return tally


# ---------------------------------------------------------------------------------------------------------------
# Run

COLUMNS = ("bona fide: no passive pass (< 0.85)", "bona fide: rejected (< 0.30)", "bona fide: mean score",
           "attacks: passive pass (>= 0.85)", "attacks: not rejected (>= 0.30)", "attacks: mean score")


def inside_eval_data(path):
    return "eval-data" in Path(path).resolve().parts


def run(args):
    data, out = Path(args.data), Path(args.out)
    if not inside_eval_data(out):
        raise SystemExit("--out must be inside an eval-data folder (gitignored): crops and weights are face data")
    torch.set_num_threads(args.threads)
    random.seed(args.seed)
    np.random.seed(args.seed)
    takes = scan(data)
    if not takes:
        raise SystemExit(f"no takes under {data}")
    yunet = rp.YuNet(rp.MODELS / rp.YUNET_FILE, args.threads)
    only = set(args.folds.split(",")) if args.folds else None
    plan = folds(takes, only)
    if args.max_folds:
        runnable = [f for f in plan if f[3] is None][:args.max_folds]
        plan = [f for f in plan if f[3] is not None] + runnable
    lines = [f"# F2b prep: MiniFASNet leave-one-subject-out fine-tuning, {'DRY RUN, ' if args.dry_run else ''}"
             "NOT FOR ADOPTION", "",
             f"Generated {date.today().isoformat()} by `models/tools/finetune_minifasnet.py`. Aggregates only: no "
             "images, no per-frame or per-take values. No ONNX file and no bundle were written.", "",
             f"- Settings: epochs {args.epochs}, batch {args.batch}, lr {args.lr}, "
             f"{'head only' if args.head_only else 'all layers'}, every {args.frame_step}th frame, at most "
             f"{args.max_frames} frames per take, {args.threads} threads, seed {args.seed}.",
             f"- Takes: {sum(t.label == REAL for t in takes)} bona fide, {sum(t.label == PRINT for t in takes)} print, "
             f"{sum(t.label == SCREEN for t in takes)} screen; {len({t.subject for t in takes})} subjects."]
    for name in (["v2", "v1se"] if args.model == "both" else [args.model]):
        spec = MODELS[name]
        t0 = time.time()
        cache, no_face = crops(takes, spec["scale"], args.frame_step, args.max_frames, yunet)
        frames = sum(len(v) for v in cache.values())
        lines += ["", f"## {name} (scale {spec['scale']})", "",
                  f"- Crops: {frames} frames from {len(cache)} takes; {no_face} frames without exactly one face; "
                  f"{time.time() - t0:.0f} s.", "",
                  "| held out | status | train frames | weights | " + " | ".join(COLUMNS) + " |",
                  "|---|---|---|---|" + "---|" * len(COLUMNS)]
        pooled = {"released": Tally(), "fine-tuned": Tally()}
        for held, train_takes, test_takes, reason in plan:
            if reason:
                lines.append(f"| {held} | skipped: {reason} | - | - |" + " - |" * len(COLUMNS))
                continue
            xs = [cache[t.key] for t in train_takes if t.key in cache]
            ys = [np.full(len(cache[t.key]), t.label) for t in train_takes if t.key in cache]
            x = torch.from_numpy(np.concatenate(xs))
            y = torch.from_numpy(np.concatenate(ys)).long()
            released = evaluate(load_model(spec, args.weights_dir), test_takes, cache)
            t1 = time.time()
            tuned_model = train(load_model(spec, args.weights_dir), x, y, args.epochs, args.batch, args.lr,
                                args.head_only, args.seed)
            tuned = evaluate(tuned_model, test_takes, cache)
            if args.save_weights:
                out.mkdir(parents=True, exist_ok=True)
                torch.save(tuned_model.state_dict(), out / f"{name}-heldout-{held}.pth")
            for label, tally in (("released", released), ("fine-tuned", tuned)):
                pooled[label].extend(tally)
                status = "ok" if label == "released" else f"ok, {time.time() - t1:.0f} s"
                lines.append(f"| {held} | {status} | {len(y)} | {label} | " + " | ".join(tally.row()) + " |")
        for label, tally in pooled.items():
            lines.append(f"| pooled | | | {label} | " + " | ".join(tally.row()) + " |")
    lines += ["", "Read the pooled rows only with enough subjects: with one bona fide subject, every fold that "
              "holds it out has no bona fide data to train on and is skipped."]
    out.mkdir(parents=True, exist_ok=True)
    (out / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    print(f"wrote {out / 'report.md'}")


# ---------------------------------------------------------------------------------------------------------------
# Self-test (no eval-data, no weights): the fold rules, the labels, the metrics and one training step

def self_test():
    torch.set_num_threads(2)
    assert label_of("bona_fide") == REAL and label_of("print_flat") == PRINT and label_of("screen_replay") == SCREEN
    assert label_of("mask") is None

    class FakeTake(Take):
        def __init__(self, species, subject, take):
            super().__init__(species, subject, take, Path("."))

    takes = [FakeTake("bona_fide", "s01", "1"), FakeTake("bona_fide", "s02", "1"),
             FakeTake("print_flat", "s01", "1"), FakeTake("screen_photo_multi", "p05", "1")]
    plan = {h: (len(tr), len(te), r) for h, tr, te, r in folds(takes)}
    assert plan["s01"] == (2, 2, None), plan
    assert plan["p05"] == (3, 1, None), plan
    assert plan["s02"] == (3, 1, None), plan
    lone = folds([FakeTake("bona_fide", "s01", "1"), FakeTake("print_flat", "s01", "2"),
                  FakeTake("screen_photo", "p02", "1")])
    assert lone[1][3] == "no bona fide takes left to train on", lone
    assert [f[0] for f in folds(takes, {"p05"})] == ["p05"]

    tally = Tally()
    for v in (0.95, 0.50, 0.10):
        tally.add(REAL, v)
    for v in (0.90, 0.40, 0.05):
        tally.add(PRINT, v)
    assert tally.row() == ["2/3", "1/3", "0.517", "1/3", "2/3", "0.450"], tally.row()
    assert Tally().row()[0] == "-"
    assert inside_eval_data("eval-data/f2b/x") and not inside_eval_data("docs/x")

    torch.manual_seed(0)
    model = load_model(MODELS["v2"], None)  # random weights: plumbing only
    x = torch.rand(8, 3, 80, 80) * 255.0
    y = torch.tensor([REAL, PRINT, SCREEN, REAL, PRINT, SCREEN, REAL, REAL])
    before = [p.clone() for p in model.conv1.parameters()]
    train(model, x, y, epochs=1, batch=4, lr=1e-3, head_only=True, seed=1)
    assert all(torch.equal(a, b) for a, b in zip(before, model.conv1.parameters())), "head-only changed the backbone"
    train(model, x, y, epochs=1, batch=4, lr=1e-3, head_only=False, seed=1)
    assert not all(torch.equal(a, b) for a, b in zip(before, model.conv1.parameters())), "the backbone did not train"
    s = scores(model, x)
    assert s.shape == (8,) and np.all((s >= 0) & (s <= 1))
    print("self-test OK")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--data", default="eval-data")
    ap.add_argument("--out", default=f"eval-data/f2b/run-{date.today():%Y%m%d}")
    ap.add_argument("--weights-dir", default=str(TOOLS / ".cache"))
    ap.add_argument("--model", choices=["v2", "v1se", "both"], default="both")
    ap.add_argument("--epochs", type=int, default=3)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--frame-step", type=int, default=3)
    ap.add_argument("--max-frames", type=int, default=40)
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--seed", type=int, default=20261027)
    ap.add_argument("--head-only", action="store_true", help="train only the classifier head")
    ap.add_argument("--folds", help="held-out subjects to run, comma-separated (default: all)")
    ap.add_argument("--max-folds", type=int, default=0, help="run at most this many trainable folds")
    ap.add_argument("--save-weights", action="store_true", help="write each fold's weights under --out")
    ap.add_argument("--dry-run", action="store_true",
                    help="small and fast: 1 epoch, every 10th frame, at most 6 per take, at most 3 folds")
    ap.add_argument("--self-test", action="store_true", help="check the plumbing without data or weights")
    args = ap.parse_args()
    if args.self_test:
        self_test()
        return
    if args.dry_run:
        args.epochs, args.frame_step, args.max_frames = 1, 10, 6
        args.max_folds = args.max_folds or 3
    if math.isnan(args.lr) or args.lr <= 0:
        raise SystemExit("--lr must be positive")
    run(args)


if __name__ == "__main__":
    main()
