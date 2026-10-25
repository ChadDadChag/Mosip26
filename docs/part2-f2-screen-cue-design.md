# F2: a second screen cue for close-up screen attacks (design note)

Status: design only, no code yet. The numbers are from one subject (s01), one attack phone and one webcam, so they
are provisional.

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
