# F2b: leave-one-subject-out fine-tuning of MiniFASNet (run on four bona fide subjects, not adopted)

F2's prototypes found no second screen cue that ships ([part2-f2-screen-cue-design.md](part2-f2-screen-cue-design.md)
section 6.4). The most direct remaining fix for close-up screens is to fine-tune the two Silent-Face models on our own
consented data (that note's section 3.4).

This page has:
- the pipeline, `models/tools/finetune_minifasnet.py`;
- a dry run on the data recorded before F9;
- the real run of 2026-10-28 on the F9 data, with four bona fide subjects.

**Outcome: not adopted.** The real run does not meet the acceptance criteria in section 4 of the F2 note.
- The fine-tuned models gained only in the folds where the attack species, screens and room were already in
  training.
- In the one fold that holds out every close-up screen take, they did not gain.

No ONNX file is exported into `models/`, and no bundle is packed or signed. A later candidate needs the same
evaluation. It would ship only as a newly signed bundle (`pad-2026.10.9` or later).

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

## What a run needs

- **Several bona fide subjects.** With one subject (s01), every fold that holds s01 out has no bona fide data left to
  train on, and every other fold is evaluated on attacks only.
  - The F9 data has four bona fide subjects (s01 to s04). The F2 note asks for 10 to 20.
- **Device hold-out**, as in the F2 note's section 4: it is not in this tool yet. Folds by subject keep a person out
  of training, not a screen or a camera. The real run (below) shows why this matters.
- **Per-species rates.** The report pools the attack species. The F2 note's security criterion is on
  `screen_photo_close` alone, so that rate is not in the report either.
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

## The real run (2026-10-28, four bona fide subjects): not adopted

- **When and how.**
  - The run was on 2026-10-28 from 19:38 to 21:42 (2 h 03 min), on the CPU with 2 threads. It came after the F9
    run, on the same `eval-data/`.
  - Options: `--model both --epochs 3 --frame-step 3 --max-frames 40 --threads 2 --subject-map
    "s02=p05,s03=p06,s04=p02"`. All layers were trained, with seed 20261027, and no weights were saved.
  - The report stays in `eval-data/f2b/`.
- **Data.**
  - 178 takes: 47 bona fide (11 from s01, 12 each from s02, s03 and s04), 57 print and 74 screen.
  - 30 subject codes give 27 folds: each of the three teammates who are also photographed people is one fold.
  - Every fold ran, taking 76 to 169 s per fold and model.
  - 2,994 crops per model; 44 of the sampled frames did not hold exactly one face.
- **The metric** is the tool's (see "What the tool does"): one model's median `softmax[1]` per take, read against 0.85
  and 0.30.
  - It is not the engine's session decision, which fuses both models with the caps, the latch and the window.
  - So these shares are not the APCER and BPCER of the ISO evaluation.

Held-out results, released → fine-tuned (one model's median `softmax[1]` per take, as above):

| Model | Folds | Bona fide takes | No passive pass (< 0.85) | Rejected (< 0.30) | Attack takes | Passive pass (≥ 0.85) | Not rejected (≥ 0.30) | Mean attack score |
|---|---|---|---|---|---|---|---|---|
| V2 (2.7) | all 27 | 47 | 5 → 5 | 0 → 0 | 131 | 18 → 9 | 33 → 17 | 0.233 → 0.111 |
| V2 (2.7) | s01's fold | 11 | 1 → 4 | 0 → 0 | 49 | 9 → 9 | 13 → 15 | 0.246 → 0.262 |
| V2 (2.7) | the other 26 | 36 | 4 → 1 | 0 → 0 | 82 | 9 → 0 | 20 → 2 | 0.225 → 0.020 |
| V1SE (4.0) | all 27 | 47 | 1 → 0 | 0 → 0 | 131 | 14 → 7 | 32 → 15 | 0.199 → 0.113 |
| V1SE (4.0) | s01's fold | 11 | 0 → 0 | 0 → 0 | 49 | 6 → 7 | 11 → 15 | 0.194 → 0.284 |
| V1SE (4.0) | the other 26 | 36 | 1 → 0 | 0 → 0 | 82 | 8 → 0 | 21 → 0 | 0.202 → 0.010 |

- The rows for the other 26 folds are sums over the report's per-fold rows.
- V2's bona fide takes with no passive pass, per subject:
  - s01: 1 → 4 of 11;
  - s02: 3 → 1 of 12;
  - s03: 1 → 0 of 12;
  - s04: 0 of 12 throughout.
- V1SE had one such take (s02's), and it went to 0.
- **The cost falls on s01.**
  - s01's fold trains on 1,833 frames, against 2,695 to 2,991 for the other folds.
  - Its only bona fide training data is s02, s03 and s04.
  - V2's mean bona fide score on s01 drops from 0.924 to 0.834: 1 → 4 of 11 takes would go to a challenge.
  - The pooled bona fide row hides this.

**What the two kinds of fold measure.**
- **s01's fold holds out whole species.** Its 49 attack takes are:
  - every take of `print_curved` (6), `print_flat` (6), `screen_photo` (6), `screen_photo_close` (12),
    `screen_replay` (6) and `screen_replay_monitor` (12);
  - 1 of the 38 `screen_photo_multi` takes.

  Training saw none of these species, only `print_multi` and `screen_photo_multi`. Fine-tuning did not help here:
  - passive passes stayed the same (V2) or rose by one (V1SE);
  - more attack takes escaped rejection.
- **The other 26 folds hold out one photographed person.**
  - Their species (`print_multi` and `screen_photo_multi`) were all in training, and so were the printer, the
    screens, the camera and the room.
  - Both models went to 0 passive passes there.
  - That is the failure mode of the F2 note's section 3.4, seen from the other side: the models fit the recording
    setup.
  - The pooled halving comes entirely from these folds.

**Against the acceptance criteria (F2 note, section 4).**

| Criterion | Result |
|---|---|
| Security: APCER 0 for `screen_photo_close` on held-out devices, with its bound | Not shown. The tool pools species, so it does not say how many of the passes left in s01's fold (9 for V2, 7 for V1SE) are `screen_photo_close`. That fold holds every such take, and its passes did not drop. There is no device hold-out |
| Bona fide: BPCER unchanged | Met: 0 of 47 rejected, before and after, for both models |
| Bona fide: at most 2 more points moved from a passive pass to a challenge | Met when pooled: no change for V2, one take fewer for V1SE (1 of 47 to 0). But V2 moved 3 more of s01's 11 takes below 0.85 (27 points for that subject; see "The cost falls on s01"), and the pooled figure hides it |
| Independence: each cue alone catches 80% of close-up attacks | Not measurable per species with this tool |
| Cost: at most 3 ms per frame | Unchanged: same architecture and size |
| Forgetting re-check (`PadEvaluation`, the F16 clips) | Not run: there is no candidate |

**Outcome: not adopted.**
- Production keeps the released weights, with the moiré cap, the latch and the volatility cap as they are today.
- No threshold, cap or bundle changes.

- **Run to run.** Earlier the same evening, a timing run of s01's fold alone used the same seed and settings.
  - V2: the same counts.
  - V1SE: 8 of 49 passive passes instead of 7, and 16 not rejected instead of 15.
  - The cause: the seed in the report does not cover dropout. The tool seeds the sampler's generator, `random` and
    `numpy`, but not torch's global generator, which the dropout layer draws from. So a re-run differs by about one
    take.
  - This is a note only; the tool is unchanged.
- **What a real test would need** (future work, not scheduled):
  - attacks recorded on screens, printers and cameras that are not in training: a device hold-out;
  - a per-species split in the tool's report;
  - more bona fide subjects, and close-up screen attacks from several people, phones and screens.
