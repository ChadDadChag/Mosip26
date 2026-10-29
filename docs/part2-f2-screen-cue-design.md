# F2: a second screen cue for close-up screen attacks (design note)

Status: design. Three cues (sections 3.1 to 3.3) were prototyped offline, and **none ships** (section 6). The
numbers are from one subject (s01), one attack phone and one webcam, so they are provisional.

## 1. The problem

A phone showing a face, held about 10 cm from the camera, fills the PAD models' crops with screen content. Both
MiniFASNet models then score it as live. Before the session moiré cap, 4 of 12 such takes passed passively, at
0.89–0.95 ([part2-iso30107-evaluation.md](part2-iso30107-evaluation.md), section 4.3).

**Today's defence is one cue: the session moiré cap** ([part2-pad-architecture.md](part2-pad-architecture.md),
section 3).
- The moiré value is `log10(max / median)` of the Hann-windowed 64 × 64 DFT magnitude of the face crop, over radii
  8–31. A screen's pixel grid adds periodic peaks to that spectrum.
- When the rolling median of the last 10 values is 1.40 or more, the frame's score becomes at most 0.60. That forces
  a challenge; it never rejects.
- With the cap, 0 of 12 close-up attacks passed passively, and no bona fide take was affected.

**Why one cue is not enough.**
- **The margin is thin.** Per take, the window median was 1.00–1.37 for bona fide and 1.435–1.593 for the close-up
  attacks: a gap of 0.065.
- **It was measured on one device pair.** Moiré comes from the screen's pixel pitch beating against the camera's
  sampling. It changes with the screen, the distance, focus and the camera's sharpening.
  - A screen with a finer pitch (a 450+ ppi phone), or a softer camera, can fall below 1.40.
  - A sharper camera, or a textured background, can push bona fide frames above it. Bona fide with a moving head
    already reached 1.45 at the 90th percentile.
- **There is no second line of defence.** The cap only forces a challenge. An attacker who can also perform the
  challenge, for example by replaying a video of the person, is then judged by PadMonitor. PadMonitor reads the same
  MiniFASNet scores that the close-up screen fools.

**The goal.** A second cue that does not depend on the screen's pixel pitch. A close-up screen attack should then be
caught when moiré misses it, without any cost to bona fide users.

## 2. Constraints

- **Plain Java 8 / Android API 21**, like the rest of the analyzer: no new native library and no new model in the
  first step.
- **The frame budget.**
  - The analyzer takes 18 ms per frame on the laptop (detect 5, mesh 7.5, PAD 4.7) and 26.6 ms (p50) on the
    Galaxy S23 FE.
  - Part 3 asks for 8 frames per second while checking passively and 15 during challenges, so 66–125 ms per frame.
  - A new cue should cost at most 3 ms per frame on the phone, or run on every second frame.
- **The same inputs.** It works on the frames Part 3 already passes (RGB or GRAY8, never mirrored), the YuNet box and
  the crops the analyzer already makes.
- **The same kind of output.**
  - A scalar statistic in `padDetails`, for the audit only (no pixels).
  - A session rule that caps the score, so it forces a challenge, as the moiré cap does.
  - A hard rejection only after the multi-device data in section 4 supports it.

## 3. Candidate cues

### 3.1 Bezel and screen edges, moving with the face

**Idea.** A phone held close still shows parts of its bezel or the screen's straight edges. The V1SE model's 4.0×
crop sees them, and so does the frame around a face that fills 20–31% of the picture. Seen at that scale, a real
face has no long, straight, high-contrast lines close around it. And when such lines do appear (a door frame, a
shelf, a monitor behind the user), they stay still while the head moves. A screen's edges move with the face, rigidly.

**How to compute it.**
1. Take the same 4.0× region that V1SE uses (`SilentFacePad.newBox`), resampled to 128 × 128 grey. V1SE itself sees
   it at 80 × 80. Compute Sobel gradients.
2. Run a coarse Hough transform: θ in 2° steps (90 bins), ρ in 2-pixel steps. Only pixels with a strong gradient
   whose direction agrees with θ vote, about 10% of the pixels.
3. Keep lines longer than 50% of the crop side, outside the face box, with a strong brightness step across them (dark
   bezel next to a bright screen).
4. Count *screen-like* line sets: two roughly parallel lines on opposite sides of the face, or two roughly
   perpendicular lines that meet near a corner.
5. Test co-motion: between consecutive frames, compare each line's displacement with the face box's displacement.
   A screen's lines move by the same amount; background lines do not.
6. Session rule: when at least 5 of the last 10 single-face frames show a screen-like line set that moves with the
   face, cap the score at 0.60.

**Cost.** About 150,000 votes per frame. Under 1 ms in Java on the phone.

**Expected failure modes.**
- **The screen fills the whole view.** Held even closer, or with a tablet, no edge is visible. The face is then very
  large (FACE_TOO_LARGE) and moiré is strongest, so the two cues overlap least where each is weakest.
- **Straight lines near a real face.** Glasses frames, a hood, a headrest, or a door frame right behind the user. The
  co-motion test removes static background. Glasses move with the face, but they are inside or at the face box, so
  the "outside the face box" rule excludes them.
- **Motion blur** smears the edges. Frames marked BLURRY are skipped, as for the passive window.
- **A frameless presentation:** a photo cut along the face outline, or a phone in a dark case against a dark
  background. These are print attacks (F3) or a low-contrast bezel. Moiré or the PAD models must catch them.

**Data it needs.** Close-up screen takes on at least 3 phones with different bezel colours. Bona fide takes with
cluttered backgrounds: door frames, shelves, a monitor behind the user.

**Prototype result:** it does not separate the close-up attacks that fill the view from bona fide (section 6).

### 3.2 Temporal flicker and banding

**Idea.**
- Screens refresh at 60–120 Hz, and many OLED phones dim by pulse-width modulation (PWM) at 240–2,000 Hz.
- A rolling-shutter camera reads rows at slightly different times, so PWM appears as horizontal bands. The camera's
  frame rate is not locked to the screen's, so the bands drift from frame to frame.
- A real face under steady light shows no periodic row pattern.

**How to compute it.**
1. In the face box, take the mean brightness of each row, resampled to 96 rows.
2. Remove shading: subtract a 9-row moving average.
3. Run a 96-point DFT and take the peak-to-median ratio over the band frequencies. Also take the peak's phase, to
   follow the drift between frames.
4. Do the same on the background, the part of the frame outside a 2× face box.
5. Score a frame as screen-like when the face shows a periodic band that the background does not, and the band's
   phase drifts across frames.

**Cost.** One pass of row sums and a short DFT: under 0.3 ms.

**Expected failure modes.**
- **No PWM.** Many LCDs, and OLEDs at high brightness, use DC dimming, so there are no bands. This cue then misses.
- **Exposure that averages the bands away.** An exposure time that is a multiple of the PWM period hides them.
  Indoors, auto-exposure often settles at 1/50 s or 1/60 s.
- **Mains flicker.** Fluorescent and cheap LED lights flicker at 100 or 120 Hz and band the whole frame, bona fide
  included. The face-versus-background comparison handles this, unless the screen fills the frame.
- **Camera-side filtering.** Global-shutter cameras show no bands, and a camera's anti-banding mode suppresses
  them on purpose.

**Data it needs.** Per phone: brightness at 30%, 70% and 100% (PWM on and off). Two or more cameras. Rooms lit by
50 Hz and 60 Hz mains lighting (fluorescent, LED) and by daylight.

**Prototype result:** no banding in the existing recordings, so no separation (section 6.2).

### 3.3 Specular and colour statistics

**Idea.**
- A screen emits light. Its white point is cold (6,500–7,500 K), its colours come from three narrow primaries, and the
  face is often brighter than the room around it.
- Its glass reflects a flat highlight that does not follow the face's shape.
- The analyzer already computes a glare value (`OnnxFaceAnalyzer.lastReplayCues()`) but does not use it.

**How to compute it.**
- On skin patches from the mesh (cheeks and forehead, about 2,000 pixels): mean normalised chromaticity, the blue
  share b / (r + g + b), and the share of pixels clipped in one channel.
- The brightness of the face compared with the background.
- The glare value, and whether the highlight stays fixed in the frame or moves with the face.

**Cost.** Under 0.5 ms.

**Expected failure modes.**
- **White balance.** The camera's auto white balance pulls the screen's white point towards neutral, which removes
  most of the colour cue.
- **Room light.** Warm tungsten, cold daylight and blue LEDs shift real skin as much as a screen does.
- **Skin tones.** A colour rule fitted on one subject is a fairness risk across skin tones. It would need a fairness
  review on a diverse dataset before any use.
- **Screen settings and accessories.** Night modes and True Tone warm the screen's white point, and matte screen
  protectors remove the highlight.

**Verdict.** Too weak and too risky as a rule of its own. It may later be one input to a combined score.

**Prototype result:** the white point is neutralised, and the only separation is a face-colour shift with no margin
(section 6.3).

### 3.4 Fine-tuning MiniFASNet on close-up screen negatives

**Idea.** The models are fooled because their training data rarely shows a screen this close. Fine-tune MiniFASNetV1SE
(4.0×) and V2 (2.7×) with close-up screen crops as attacks and close bona fide crops as live faces.

**What it needs.**
- **Licence.** The released weights are Apache-2.0 (see `models/LICENSES.md`), so fine-tuned weights can be
  redistributed.
- **Data.**
  - The public anti-spoofing sets that would help (CelebA-Spoof and others) are for non-commercial research. A model
    MOSIP ships needs its own consented data.
  - At least 50–100 subjects, several phones, tablets and monitors, several distances, and the consent and data
    protection that biometric data requires.
- **Pipeline.** A PyTorch training and export setup outside the engine. The result is new ONNX files in a newly
  signed bundle, which the online update (`ModelUpdater`) can deliver.

**Cost.** Nothing per frame, since the models stay the same size. Weeks of data collection and training.

**Expected failure modes.**
- **Unseen devices.** It may fit the devices it was trained on, which is why the evaluation in section 4 holds
  devices out.
- **Forgetting.** It may lose accuracy on attacks it handles today (prints, screens at normal distance), so those
  must be re-tested.
- **Bias.** Accuracy may differ across skin tones and ages if the data is not diverse.

## 4. Evaluation plan

**Recordings.** With `DatasetRecorder` and consent, stored in the gitignored `eval-data/` only.

| Dimension | What to record |
|---|---|
| Attack screens | at least 4 phones (2 OLED, 2 LCD, 300 to over 450 ppi), 1 tablet, 1 laptop screen, 1 external monitor (this also covers F5, `screen_replay_monitor`) |
| Distance | 10 cm, 20 cm and 30–40 cm, with the live face-size readout |
| Content | the subject's own photo and video, and a public-domain portrait |
| Screen brightness | 30%, 70% and 100% |
| Cameras | the laptop webcam, the Android phone's front camera, and one more webcam |
| Lighting | daylight, LED ceiling light, fluorescent light (mains flicker), dim room |
| Bona fide | at least 10 subjects (20 is the target), varied skin tones, with and without glasses; plain and cluttered backgrounds; normal and close distance; still and moving head |
| Takes | 3 takes of about 10 s per condition |

**Method.**
- For each cue: its per-frame and per-session distributions, by species and by condition.
- Then the session rules alone and combined with moiré, reported as in the ISO/IEC 30107-3 evaluation: APCER per
  species, BPCER, and the share of bona fide sessions moved from a passive pass to a challenge.
- **Hold devices out.** Tune on half of the attack screens and cameras, and report on the other half.

**Acceptance criteria.**
- **Security.** No close-up screen attack passes passively on the held-out devices (APCER 0 for
  `screen_photo_close`, with its one-sided 95% bound reported).
- **Bona fide.**
  - No extra rejection: BPCER is unchanged.
  - At most 2 more percentage points of bona fide sessions moved from a passive pass to a challenge.
- **Independence.** Each cue alone catches at least 80% of the close-up attacks, so one cue missing still leaves the
  other.
- **Cost.** The new cue adds at most 3 ms per frame at p95 on the S23 FE. Measure it on a low-end phone as well (F11).

**Order of work.**
1. **Bezel and edges with co-motion.** Prototype it offline first, on the 12 close-up attacks and 11 bona fide takes
   already in `eval-data/`, with the evaluation tools. Then record the new data.
2. **Banding**, with the face-versus-background comparison. It is strongest on PWM OLED phones, which are also the
   fine-pitch screens where moiré is weakest.
3. **Fine-tuning MiniFASNet**, once the multi-subject dataset exists. That dataset is needed anyway to make the
   thresholds less provisional (F9).
4. **Colour and specular statistics**, only as inputs to a combined score, and only after a fairness review.

## 5. Recommendation

**Implement the bezel and edge cue, with the co-motion test, first.** Add it as a second session cap with the same
mechanism as the moiré cap: it forces a challenge and never rejects. Combine it with moiré by OR.

Why this one first:
- **Its physics differ from moiré's.** It does not depend on the pixel pitch, so it covers fine-pitch screens, where
  moiré is weakest.
- **It is cheap:** under 1 ms per frame.
- **It is fair by construction.** It uses neither colour nor skin.
- **It is easy to audit:** a line count and a co-motion flag in `padDetails`.
- **It can be tried today** on the existing recordings, before new data is collected.

Its known gap is a screen that fills the whole view. Moiré is strongest exactly there, and banding is the next cue
to cover it.

**Update after the prototype (section 6):** the bezel and edge cue does not ship. The gap above turned out to be
exactly the case it needed to catch.

## 6. Prototype results

### 6.1 Bezel and edges with co-motion (section 3.1): does not ship

**What was built.** `models/tools/f2_edge_cue_prototype.py`, a development tool only. It is not part of the
executable spec, and no Java mirrors it. It implements section 3.1 without a Hough transform, using only arithmetic
and comparisons:
- **Lines, per frame.** The V1SE 4.0× region at 128 × 128 grey, then Sobel gradients. Strong pixels outside the face
  box are split into near-vertical and near-horizontal families. Non-maximum suppression runs across the edge.
  Sheared projections use slopes −0.4 to 0.4 and 2-pixel bins. A line needs votes over at least half the crop side.
  Each line is placed in frame coordinates with its side of the face.
- **Co-motion, between consecutive frames.** When the face moves at least 2 pixels, a matched line is "co-moving" if
  its displacement is within half the face's displacement of it, and "static" if it moved less than half.
- **Session statistic.** `edgeScore` is the number of sides of the face (0 to 4) with a co-moving line. The session
  statistic is its rolling median over the last 10 single-face frames, as for moiré.

**Data.** The 35 takes of s01 in `eval-data/` (2,100 frames). Aggregates only.

**Results at the design setting** (Sobel magnitude at least 96, a clean step of 24 grey levels; a line needs 64
votes). "Session max" and "session median" are the maximum and median of the rolling median over a take. The
threshold with no bona fide cost is the bona fide maximum plus 0.5, which is 2.5.

| Group | Takes | Session max | Session median | Takes caught at 2.5 | Moiré, median per take |
|---|---|---|---|---|---|
| Bona fide, close | 2 | 0.0 | 0.0 | 0/2 | 1.04–1.10 |
| Bona fide, close_whole | 2 | 0.0 | 0.0 | 0/2 | 1.02–1.03 |
| Bona fide, far | 2 | 2.0 | 1.0 | 0/2 | 1.21–1.29 |
| Bona fide, moving | 2 | 0.5–1.0 | 0.0 | 0/2 | 1.27–1.31 |
| Bona fide, normal | 3 | 0.0–1.0 | 0.0 | 0/3 | 1.18–1.22 |
| Close-up, screen fills the view (`*_fill`) | 6 | 0.0 | 0.0 | 0/6 | 1.44–1.59 |
| Close-up, small face on the screen | 6 | 2.0–4.0 | 1.0–2.2 | 3/6 | 1.54–1.58 |
| `screen_photo` | 6 | 1.0–4.0 | 1.0–3.0 | 2/6 | 1.50–1.68 |
| `screen_replay` | 6 | 1.5–2.0 | 1.0 | 0/6 | 1.20–1.44 |

**More sensitive settings** rule out a threshold artefact:

| Setting (Sobel, votes) | Bona fide max | Threshold, no bona fide cost | `*_fill` caught | `*_fill` session median |
|---|---|---|---|---|
| 64, 48 | 2.5 | 3.0 | 1/6 | 0.0–1.5 |
| 48, 38 | 2.0 | 2.5 | 4/6 | 1.0–2.0 |
| 32, 32 | 2.5 | 3.0 | 5/6 | 1.0–2.5 |

At every setting, bona fide `far` has a session median (1.0–2.0) inside the `*_fill` range. So a catch is
momentary, and there is no margin.

**Why it fails.**
1. **No edge to see.** When the screen fills the view, no bezel or screen edge is in the picture. There are no lines
   in any `nasa_fill` frame, and none in 77–100% of the `selfie_fill` frames. Section 3.1 predicted this failure.
2. **The wrong lines.** At the sensitive settings, the lines found in `*_fill` are low-contrast content of the
   displayed photo, and they vanish at the 24-level step. They come from the chosen photo, not from screens, so they
   would not generalise.
3. **The body moves with the head.** The subject's torso and arms inside the 4.0× region move with the face. In the
   bona fide `far` takes, the left or right side is co-moving in 25–83% of frames at the design setting, and in
   57–95% at the sensitive ones. Section 3.1 did not foresee this.

**Independence from moiré.** There is none that helps.
- The cue fires where moiré is already strong: the small-face close-up and `screen_photo` takes, with moiré 1.50–1.68.
- It is silent where moiré is weak: `*_fill`, 1.44–1.59.
- On `screen_replay` (moiré 1.20–1.44) it reaches only the level of bona fide `far`, and those takes are already
  rejected by the PAD models.

**Decision.** It does not ship, and there is no `edgeCapThreshold`. A test of planar motion (the whole region
moving under one homography, as a flat presentation does) would need a body mask because of reason 3. Banding
(section 3.2) is next, since it targets the screen that fills the view.

### 6.2 Banding (section 3.2): does not ship

**What was built.** `models/tools/f2_banding_prototype.py`, a development tool only. Per frame, for the V1SE 4.0×
region and for the face box's central columns:
- **Row profile.** The mean luma of each frame row, in native rows. There is no resampling, so the JPEG 8-row block
  grid stays at exactly 1/8 cycle per row and can be left out.
- **Spectrum.** Quadratic shading removed, a Hann window, then the power spectrum.
- **Whitening.** Each bin is divided by the median of its ±8 neighbours, so a band shows as a narrow peak above the
  scene's natural slope. The band of interest is periods of 6 to 60 rows: PWM at about 300–2,000 Hz with a row time
  of 50–70 µs. The JPEG frequencies are left out.
- **Scores.** `bandScore` is the largest whitened peak. The temporal check `driftScore` takes the last 10 frames:
  for each frequency it multiplies the mean whitened power by (1 − phase coherence) in frame coordinates. A screen's
  band keeps its power while its phase drifts. Scene structure keeps its phase.
- **Session statistic.** The rolling median of `bandScore` over 10 single-face frames, as for moiré.

**Data.** The same 35 takes. Aggregates only.

**Results.** Each value is a session statistic. The threshold with no bona fide cost is the bona fide maximum.

| Group | Takes | `bandScore`, 4.0× region (session median) | `bandScore`, face (session median) | `driftScore` (session median) | Moiré |
|---|---|---|---|---|---|
| Bona fide, all conditions | 11 | 4.63–7.70 | 3.27–17.53 | 0.10–2.70 | 1.02–1.31 |
| Close-up, screen fills the view (`*_fill`) | 6 | 4.53–6.94 | 3.80–4.92 | 1.77–3.40 | 1.44–1.59 |
| Close-up, small face on the screen | 6 | 3.51–7.70 | 0.00–3.40 | 1.17–2.22 | 1.54–1.58 |
| `screen_photo` | 6 | 4.28–5.51 | 0.00–5.19 | 1.71–2.39 | 1.50–1.68 |
| `screen_replay` | 6 | 5.90–11.17 | 4.30–14.99 | 1.85–3.00 | 1.20–1.44 |
| **`*_fill` takes above the bona fide maximum** | | 0/6 (maximum 10.28) | 0/6 (maximum 19.54) | 0/6 (maximum 7.07) | |

There is no banding to find. The take-average spectra of the six `*_fill` takes show no narrow peak at a common
period: the strongest is 1.6–3.0 times its neighbours. A PWM band has a fixed period, set by the PWM frequency and
the row time, so averaging over frames would keep it. The strongest narrow peaks belong to bona fide `normal`
(6.6–17 times), from the room and the face's own structure.

**Does the JPEG q95 limit the cue?** Only for very faint bands. Synthetic horizontal bands were added to the
`close_whole` and `*_fill` frames, then measured directly and after a JPEG q95 4:4:4 round trip:
- 0.5 grey levels at a 7–12-row period: detected in 70–100% of frames before JPEG, and 5% after (the same as with no
  band).
- 1 grey level or more at 7–12 rows: 100% before and after.
- Periods of 20–40 rows are hidden by the scene's own row structure, with or without JPEG. They need 2–4 grey levels
  or more.

So the recordings could hide only bands under about 1 grey level. Either the attack phone does not band in these
conditions (DC dimming, or an exposure long enough to average the PWM), or its bands are fainter than that. A
lossless recording at 30 fps, with the screen at 30% brightness (PWM on), would settle it.

**Lossless confirmation.** Three 3-second PNG takes (`--only f2_banding`), 90 frames each, none repeated, at
28.5–28.9 effective fps:
- `nasa_fill_pwm30`: the phone close with the NASA portrait at 30% brightness;
- `nasa_fill_100`: the same at 100%;
- `close_whole_30fps`: the subject's own face, close.

The banding tool's `--takes` mode gave these results:
- **At 30% the band is there.** A period of about 17 rows (16.5–17.7) is among the three strongest peaks of the
  take-average spectrum in all three areas:

  | Area | Peaks at about 17 rows |
  |---|---|
  | 4.0× region | 3.0× and 2.9× |
  | Face | 3.8× and 3.1× |
  | Whole frame | 2.7× and 2.5× |

  At a row time of 50–70 µs, that is PWM at about 0.8–1.2 kHz.
- **At 100% it is gone.** No period is common to the three areas, and the strongest peaks are 2.0–2.7×.
- **It is too faint to use.** The session maxima at 30% are below the round-1 bona fide maxima, so no threshold
  catches the screen without failing real faces:

  | Statistic | Screen at 30% | Round-1 bona fide maximum |
  |---|---|---|
  | `bandScore`, 4.0× region | 8.16 | 10.28 |
  | `bandScore`, face | 6.29 | 19.54 |
  | `driftScore` | 3.26 | 7.07 |

  The bona fide take here peaks as high as the screen's band (3.4–4.3× at 27–28 and 11 rows), though its
  `driftScore` is only 0.90.
- **The decision stands: banding does not ship.** A usable band would have to be several times stronger, for
  example from a phone with low-frequency PWM or a camera with a shorter row time. An attacker avoids it anyway by
  setting the screen to full brightness.

### 6.3 Colour and white point (section 3.3): does not ship

**What was built.** `models/tools/f2_colour_prototype.py`, a development tool only. Per frame, from the RGB pixels,
with no skin model:
- `blueFace`: the blue share b / (r + g + b) of the face box's centre;
- `blueWhite`: the blue share of the brightest 5% of the 4.0× region, the scene's white point;
- `satRegion`: the 95th percentile of the saturation in the 4.0× region;
- `clipRegion`: the share of pixels with one channel clipped and another not.

Each has a rolling median over 10 single-face frames.

**Results.**
- **The white point is neutral.** `blueWhite` of the `*_fill` takes (0.330–0.346) lies inside the bona fide range
  (0.320–0.358). The camera's automatic white balance pulls the screen's cold white to neutral, as section 3.3
  expected.
- **The face is warmer, not bluer.** `blueFace` of the `*_fill` takes lies below the bona fide minimum (0.302) in
  6/6 takes: 0.214–0.268 for the subject's selfie, 0.287–0.300 for the NASA portrait. This is the opposite of the
  hypothesis. It comes from the displayed photo's own colour and the white balance shift, not from the screen's
  primaries. The margin is 0.002.
- **Saturation and clipping do not separate.** `satRegion` and `clipRegion` catch 0/6 `*_fill` takes.

**Decision.** It does not ship. The only separation is a face-colour rule with no margin, which depends on the
photo, the room light and the skin tone. That is exactly the fairness risk of section 3.3, and one subject cannot
bound it.

### 6.4 Where this leaves F2

- **Today.** The session moiré cap remains the only screen cue. Its margin on `*_fill` is the risk to watch: the
  median moiré of the `selfie_fill` takes is 1.44–1.49, against the 1.40 cap.
- **Next step.** The lossless takes (section 6.2) show that this phone bands at 30% brightness, but too faintly to
  use. The multi-device recordings of section 4, lossless at 30 fps with PWM phones at low brightness, would show
  whether any common phone bands strongly enough.
- **Next cue.** Fine-tuning MiniFASNet on close-up screens (section 3.4) is the most direct fix for the case that
  fills the view, and needs the multi-subject dataset.
- **Update, 2026-10-28: fine-tuning not adopted.** It ran on the F9 data: four bona fide subjects, leaving one
  subject out ([part2-f2b-finetune-prep.md](part2-f2b-finetune-prep.md)).
  - It gained only in folds where the attack species and devices were in training.
  - It did not gain in the fold that holds every close-up screen take.
  - A real test needs the held-out devices of section 4.
