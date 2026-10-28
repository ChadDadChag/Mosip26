# F2b prep: leave-one-subject-out fine-tuning of MiniFASNet (pipeline only, not adopted)

F2's prototypes found no second screen cue that ships ([part2-f2-screen-cue-design.md](part2-f2-screen-cue-design.md)
section 6.4). The most direct remaining fix for close-up screens is to fine-tune the two Silent-Face models on our own
consented data (that note's section 3.4).

This page is the **pipeline only**:
- `models/tools/finetune_minifasnet.py`;
- a dry run on the data recorded so far.

Nothing is adopted: no ONNX file is exported into `models/`, and no bundle is packed or signed. The real run comes after
the F9 teammate recordings. Even then, a candidate needs the evaluation of the F2 note's section 4, and it would
ship only as a newly signed bundle (`pad-2026.10.9` or later).

## What the tool does

- **Frames.** Every take in `eval-data/` (`bona_fide/` and `attack/<species>/`), every Nth frame, at most M per take.
  A frame is kept only when YuNet finds exactly one face, counted as the analyzer counts faces.
- **Crops.** The engine's own crop (`reference_pipeline.get_new_box` and `pad_input`): scale 2.7 for V2 and 4.0 for
  V1SE, 80 x 80, BGR, raw 0..255. Training sees what the engine sees.
- **Labels.** Silent-Face's three-class head: bona fide 1 (the engine reads `softmax[1]`), prints 0, screens 2.
- **Folds: leave one subject out.** A subject is the person whose face the take shows:
  - the participant's bona fide takes and their own photos are `sNN`;
  - a photographed person is their `pXX`, `qXX` or `uXX`;
  - a teammate who is also a photographed person has both codes. `--subject-map s02=p05,...` (the same entries as
    BindingEvaluation's) makes them one fold, `s02+p05`, so that face is never on both sides. Without a map, the
    report warns when teammates and photographed people both appear.
- **Each fold.**
  - Training starts from the released weights. It uses cross-entropy, class-balanced sampling, and a flip and
    brightness/contrast augmentation.
  - It runs on all layers, or on the classifier head only (`--head-only`), on the CPU with 2 threads by default.
  - The held-out subject's takes are then scored by both the released and the fine-tuned weights.
  - A fold is skipped, and reported, when its training set has no bona fide or no attack takes.
- **Metrics.** Per take: the median `softmax[1]` of its frames, read against the engine's thresholds.
  - Bona fide takes: the share with no passive pass (below 0.85), and the share rejected (below 0.30).
  - Attack takes: the share that would pass passively (0.85 or above), and the share not rejected (0.30 or above).
  - Mean scores per class.
  - Reported per fold and pooled over the folds.

Run it from the Mosip26 root, in the `mosip-liveness` venv:

    python -I models/tools/finetune_minifasnet.py --self-test
    python -I models/tools/finetune_minifasnet.py --data eval-data --out eval-data/f2b/dryrun --dry-run
    python -I models/tools/finetune_minifasnet.py --data eval-data --out eval-data/f2b/run-<date> --model both --epochs 3
        [--subject-map s02=p05,...]

## Privacy

Crops are face images. They exist only in memory. `--out` must be below this repository's own `eval-data` folder
(gitignored), and the tool refuses anything else, including another folder named `eval-data`. The report holds
counts, rates and means only: no image, and no per-frame or per-take value. Fine-tuned weights are written only with
`--save-weights`, and only under `--out`. They are trained on people's faces, so they stay in `eval-data/` until a
bundle decision.

## What the real run needs

- **Several bona fide subjects.** With one subject (s01), every fold that holds s01 out has no bona fide data left to
  train on, and every other fold is evaluated on attacks only. The pooled numbers mean something only with the F9
  teammates (at least 3, and the F2 note asks for 10 to 20).
- **Device hold-out**, as in the F2 note's section 4: it is not in this tool yet. Folds by subject keep a person out
  of training, not a screen or a camera.
- **Forgetting.** Each candidate is re-run on the attacks the released models already handle (`PadEvaluation`, the F16
  clips) before anyone considers it.

## Dry run

2026-10-27, `--dry-run`: 1 epoch, every 10th frame, at most 6 frames per take, at most 3 trainable folds, CPU with 2
threads. The run used 134 takes (11 bona fide, 57 print, 66 screen) from 27 subjects. That gave 631 crops per model
from 133 takes; 9 sampled frames did not hold exactly one face. The report stays in `eval-data/f2b/`.

| Model | Folds run | Held-out attack takes | Passive pass, released → fine-tuned | Not rejected, released → fine-tuned | Mean attack score, released → fine-tuned |
|---|---|---|---|---|---|
| V2 (2.7) | 3 (p01, p02, p03) | 16 | 2 → 1 | 5 → 1 | 0.257 → 0.066 |
| V1SE (4.0) | 3 (p01, p02, p03) | 16 | 2 → 1 | 2 → 1 | 0.129 → 0.068 |

- **The pipeline works end to end:** crops, folds, fine-tuning, scoring and the report. Each fold took about 10 s per
  model, and the crops took 22–26 s per model.
- **The fold that holds out s01 was skipped,** as designed: it leaves no bona fide data to train on. So no fold
  scored bona fide takes. Training on one person's bona fide takes and every attack can push attack scores down with
  no visible cost. That cost is exactly what the real run must measure, with several bona fide subjects.
- **These numbers say nothing about adoption.** The dry run used 1 epoch on a few frames, a single bona fide subject,
  and held-out attack identities only.
