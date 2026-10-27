"""F9 re-evaluation over the multi-subject data, with decision rules fixed before the data (development only).

scripts/f9-reeval.ps1 runs it around PadEvaluation and BindingEvaluation:

    python f9_reeval.py inventory --data eval-data --subjects s01,s02 --out <dir>
    python f9_reeval.py analyze --data eval-data --subjects s01,s02 --pad <dir>/pad --binding <dir>/binding
                                --sessions eval-data/sessions --out <dir>
    python f9_reeval.py --self-test

inventory refuses (exit 2) a listed subject without bona fide takes or without the consent marker in every take,
and a bona fide subject in the data that is not listed. analyze replays the session guards over the recorded takes
(PadEvaluation's frames.csv) and the live logs, applies RULES, and writes summary.md: aggregates and codes only, no
frame path and no name. The rules below are the only copy; f9-reeval.ps1 carries them verbatim (checked by the
self-test) and summary.md prints them.
"""
import argparse
import collections
import csv
import datetime
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

RULES = """\
Pre-registered decision rules, fixed on 2026-10-28 before the F9 teammate data (models/tools/f9_reeval.py).
R0 Consent. Every bona fide take of a listed subject has "consent": true in its meta.json, or the subject is
   refused and the run stops. Subjects are codes (sNN), never names. A bona fide subject in the data that is not
   listed also stops the run: PadEvaluation reads every subject.
R1 PAD thresholds. PadEvaluation with the current defaults: APCER per species, BPCER pooled and per subject at
   passive 0.85 and reject 0.30, and its recommended thresholds. Reported only: this run adopts no threshold.
R2 F18 volatility cap. Share = the listed subjects' recorded real-face takes whose first passive window passes
   without the guards but not with them (latch, volatilityMinScore 0.85), pooled. Above 25%: evaluate 0.80, by
   replaying every recorded attack take and every attack attempt of the live logs (all SessionDemo cases except
   the real-face a, b, c, h, i, j, k, l and the mixed m) at 0.85 and at 0.80. Adopt 0.80 only if no attack
   verdict changes; otherwise keep 0.85 and record the cost (the share). At 25% or below: keep 0.85. Bona fide
   PAD rejections (ATTACK) are reported per subject.
R3 F2 moire cap on new faces. Per subject, the share of scored bona fide frames (one face, model outputs) that the
   moire cap capped. Above 10%: the subject is flagged. A flag is report only: the subject's code goes into the
   limitations in docs/part2-iso30107-evaluation.md, and the moire cap does not change this week.
R4 F15 identity threshold. BindingEvaluation's rule (at least 3 people and 50 onboarding-like pairs), printed
   verbatim, decidable or not. Labels count as confirmed only with -LabelsConfirmed. At about 11 onboarding-like
   pairs per person, s01 and 3 teammates give about 44 pairs, under 50: "not decidable, 0.40 stays" is the
   expected outcome, and the 50-pair bar is not lowered after the fact.
R5 Size. A result that changes production (R2: adopt 0.80) needs at least 3 subjects. With fewer, it is
   provisional and nothing changes.
R3 action and readings 1-7 confirmed by the planner on 2026-10-28, before the F9 teammate data.
"""

F18_DEFAULT, F18_CANDIDATE, F18_SHARE_LIMIT = 0.85, 0.80, 0.25
MOIRE_FLAG = 0.10
MIN_SUBJECTS = 3
# SessionDemo's cases (its class comment): real faces, m (a real face beside a printed cut-out), and the attacks,
# with F5's second-screen replay z and zf. A log's "case" is the case code followed by the run number ("a14", "zf6").
REAL_FACE_CASES = {"a", "b", "c", "h", "i", "j", "k", "l"}
MIXED_CASES = {"m"}
ATTACK_CASES = set("defgnopqrstuvwxy") | {"z", "zf"}


def case_code(logged_case):
    """The SessionDemo case code of a logged "case": "a14" -> "a", "zf6" -> "zf"."""
    m = re.match(r"[a-z]+", str(logged_case))
    return m.group(0) if m else str(logged_case)


def is_live_attack(run):
    """Whether a live run is an attack; a case outside SessionDemo's lists stops the run (R2 names its cases)."""
    code = case_code(run.get("case"))
    if code in ATTACK_CASES:
        return True
    if code in REAL_FACE_CASES | MIXED_CASES:
        return False
    raise Refused("unknown SessionDemo case '%s' in a live log: R2 counts only SessionDemo's cases" % run.get("case"))


PROTOCOL = collections.OrderedDict([("normal", 3), ("close", 2), ("far", 2), ("dim", 1), ("moving", 2),
                                    ("close_whole", 2)])  # run sheet section 7
SUBJECT = re.compile(r"^s\d\d$")


class Refused(Exception):
    """A step that must stop the run, with the message the operator sees."""


# ---------------------------------------------------------------------------------------------------------------
# R0 inventory
# ---------------------------------------------------------------------------------------------------------------

def inventory(data, subjects):
    """{subject: {condition: takes}} for the listed subjects; raises Refused (R0)."""
    for s in subjects:
        if not SUBJECT.match(s):
            raise Refused("subject '%s' is not a code like s02 (codes only, never names)" % s)
    twice = sorted(s for s, n in collections.Counter(subjects).items() if n > 1)
    if twice:
        raise Refused("subject(s) listed more than once: %s (R5 counts people)" % ", ".join(twice))
    root = Path(data) / "bona_fide"
    if not root.is_dir():
        raise Refused("no bona fide takes: %s does not exist" % root)
    unlisted = sorted(p.name for p in root.iterdir() if p.is_dir() and p.name not in subjects)
    if unlisted:
        raise Refused("bona fide subjects in the data but not listed: %s. List them in -Subjects or move them out of "
                      "%s (PadEvaluation reads every subject)" % (", ".join(unlisted), root))
    inv = collections.OrderedDict()
    for s in subjects:
        takes = sorted((p for p in (root / s).iterdir() if p.is_dir()), key=lambda p: (len(p.name), p.name)) \
            if (root / s).is_dir() else []
        if not takes:
            raise Refused("%s: no bona fide takes in %s" % (s, root / s))
        conditions, unconsented = collections.Counter(), []
        for t in takes:
            meta = t / "meta.json"
            m = json.loads(meta.read_text(encoding="utf-8")) if meta.is_file() else {}
            if m.get("consent") is not True:
                unconsented.append(t.name)
            if m.get("subject", s) != s:
                raise Refused("%s: take %s says subject %s" % (s, t.name, m.get("subject")))
            conditions[m.get("condition", "unknown")] += 1
        if unconsented:
            raise Refused("%s: no consent marker in take(s) %s. The subject is refused, not skipped"
                          % (s, ", ".join(unconsented)))
        inv[s] = dict(conditions)
    return inv


def protocol_gaps(conditions):
    """Takes short of the run sheet's 12 per condition, e.g. {"dim": 1}."""
    return {c: n - conditions.get(c, 0) for c, n in PROTOCOL.items() if conditions.get(c, 0) < n}


# ---------------------------------------------------------------------------------------------------------------
# R2 F18 volatility cap, R3 moire cap
# ---------------------------------------------------------------------------------------------------------------

def first_attempts(runs, cfg, replay):
    """Per run: (run, verdict without guards, verdict with cfg) of its first attempt."""
    out = []
    for run in runs:
        rows, _ = replay(run, cfg)
        out.append((run, rows[0][2], rows[0][4]))
    return out


def all_attempts(runs, cfg, replay):
    """Every attempt, in order: (id, verdict with cfg). The id is codes only: log or "recorded", case, item."""
    out = []
    for run in runs:
        rows, _ = replay(run, cfg)
        for r in rows:
            out.append(("%s %s %s attempt %d" % (run.get("_log", "recorded"), run["case"], run.get("item"), r[0]),
                        r[4]))
    return out


def f18_counts(bona_fide_85, bona_fide_80):
    """Per subject: passes without guards, turned at 0.85 and 0.80, PAD rejections at 0.85 and 0.80, no window."""
    c = collections.OrderedDict()
    for (run, before, v85), (_, _, v80) in zip(bona_fide_85, bona_fide_80):
        k = c.setdefault(run["subject"], collections.Counter())
        k["takes"] += 1
        if before is None:
            k["no_window"] += 1
        if before == "PASS":
            k["passes"] += 1
            k["turned85"] += v85 != "PASS"
            k["turned80"] += v80 != "PASS"
        k["reject85"] += v85 == "ATTACK"
        k["reject80"] += v80 == "ATTACK"
    return c


def share(turned, passes):
    return None if passes == 0 else turned / passes


def f18_decision(pooled_share85, attack_changes, n_subjects, attacks_replayed):
    """(code, text) of rule R2 with R5. attacks_replayed: attack attempts with a passive window at 0.85 or 0.80."""
    if pooled_share85 is None:
        return "NOT_DECIDABLE", "not decidable: no real-face take passes without the guards"
    if pooled_share85 <= F18_SHARE_LIMIT:
        code, text = "KEEP", "keep %.2f (share %s at or below 25%%)" % (F18_DEFAULT, pct(pooled_share85))
    elif attacks_replayed == 0:
        return "NOT_DECIDABLE", ("not decidable: share %s above 25%%, but no attack attempt with a passive window to "
                                 "replay at %.2f; keep %.2f" % (pct(pooled_share85), F18_CANDIDATE, F18_DEFAULT))
    elif attack_changes:
        code, text = "KEEP_WITH_COST", ("keep %.2f: at %.2f %d attack verdict(s) change; cost recorded: %s of "
                                        "real-face passes become challenges" % (F18_DEFAULT, F18_CANDIDATE,
                                                                                len(attack_changes),
                                                                                pct(pooled_share85)))
    else:
        code, text = "ADOPT_080", "adopt %.2f: share %s above 25%% and no attack verdict changes" % (
            F18_CANDIDATE, pct(pooled_share85))
    if n_subjects < MIN_SUBJECTS:
        text += "; provisional (N = %d < %d): nothing changes" % (n_subjects, MIN_SUBJECTS)
        if code == "ADOPT_080":
            code = "PROVISIONAL_ADOPT_080"
    return code, text


def moire_shares(frames_csv, subjects):
    """{subject: (capped, scored)} over bona fide frames with one face and both model outputs."""
    out = collections.OrderedDict((s, [0, 0]) for s in subjects)
    with open(frames_csv, encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            if r["species"] != "bona_fide" or r["subject"] not in out:
                continue
            if r["faces"] == "1" and r["v2"] not in ("", "NaN") and r["v1se"] not in ("", "NaN"):
                out[r["subject"]][1] += 1
                out[r["subject"]][0] += r["moire_cap"] == "1"
    return out


def moire_flagged(capped, scored):
    return scored > 0 and capped / scored > MOIRE_FLAG


def r3_result(flagged):
    """Rule R3's result: report only."""
    if not flagged:
        return "no subject flagged"
    return ("flagged: %s; report only: into the limitations of docs/part2-iso30107-evaluation.md, the moire cap does "
            "not change this week" % ", ".join(flagged))


# ---------------------------------------------------------------------------------------------------------------
# R1 PAD, R4 F15: the tools' own report sections
# ---------------------------------------------------------------------------------------------------------------

def section(markdown, heading_prefix):
    """The '## ...' section whose heading starts with heading_prefix, up to the next '## ' heading."""
    lines, out, inside = markdown.splitlines(), [], False
    for line in lines:
        if line.startswith("## "):
            if inside:
                break
            inside = line[3:].startswith(heading_prefix)
        if inside:
            out.append(line)
    return "\n".join(out).strip()


def bona_fide_zones(scores_csv, subjects):
    """{subject: Counter(zone)} of the bona fide takes, production candidate (PadEvaluation's zone column)."""
    out = collections.OrderedDict((s, collections.Counter()) for s in subjects)
    with open(scores_csv, encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            if r["species"] == "bona_fide" and r["subject"] in out:
                out[r["subject"]][r["zone"]] += 1
    return out


def pct(x):
    return "-" if x is None else "%.1f%%" % (100 * x)


def verdict_counts(attempts):
    """'ATTACK 94, CHALLENGE 40, PASS 0, no window 119' over (id, verdict) pairs."""
    c = collections.Counter(v for _, v in attempts)
    return ", ".join("%s %d" % (k, c[k]) for k in ("ATTACK", "CHALLENGE", "PASS")) + ", no window %d" % c[None]


def frac(a, b):
    return "%d/%d (%s)" % (a, b, pct(share(a, b)))


# ---------------------------------------------------------------------------------------------------------------
# summary
# ---------------------------------------------------------------------------------------------------------------

def summary(subjects, inv, zones, counts, attack85, attack80, moire, pad_md, binding_md, sources):
    n = len(subjects)
    pooled = collections.Counter()
    for c in counts.values():
        pooled.update(c)
    share85 = share(pooled["turned85"], pooled["passes"])
    share80 = share(pooled["turned80"], pooled["passes"])
    # The same attempts in the same order at both values.
    changes = [(i, v85, v80) for (i, v85), (_, v80) in zip(attack85, attack80) if v85 != v80]
    newly_passing = [c for c in changes if c[2] == "PASS" and c[1] != "PASS"]
    replayed = sum(1 for (_, v85), (_, v80) in zip(attack85, attack80) if v85 is not None or v80 is not None)
    code, f18_text = f18_decision(share85, changes, n, replayed)
    flagged = [s for s, (cap, sc) in moire.items() if moire_flagged(cap, sc)]
    zones_pooled = collections.Counter()
    for z in zones.values():
        zones_pooled.update(z)
    responded = sum(zones_pooled[k] for k in ("PASSIVE_PASS", "CHALLENGE", "PAD_REJECT"))
    f15 = section(binding_md, "F15 identity threshold rule")
    f15_result = next((l.replace("**", "").strip() for l in f15.splitlines() if l.startswith("**Result")),
                      "no rule output")
    recommended = section(pad_md, "Recommended thresholds")
    rec_line = next((l[2:] for l in recommended.splitlines() if l.startswith("- Candidate 1 ")), "not reported")

    b = []
    banner = "N = %d subject%s (%s)" % (n, "" if n == 1 else "s", ", ".join(subjects))
    b.append("# F9 re-evaluation: %s%s\n" % (banner, "" if n >= MIN_SUBJECTS else
                                               ": not decidable / provisional where the rules need %d" % MIN_SUBJECTS))
    b.append("Generated %s. Sources: %s. Aggregates and codes only.\n" % (
        datetime.datetime.now().strftime("%Y-%m-%d %H:%M"), "; ".join(sources)))
    b.append("## Pre-registered rules\n\n```\n%s```\n" % RULES)
    b.append("## Decisions\n")
    b.append("| rule | numbers | result |\n|---|---|---|")
    b.append("| R0 consent | %s | all listed subjects consented |" % ", ".join(
        "%s %d takes" % (s, sum(c.values())) for s, c in inv.items()))
    b.append("| R1 PAD thresholds | bona fide pooled at 0.85/0.30: pass %s, challenge %s, PAD reject (BPCER) %s, "
             "non-response %d; recommended: %s | report only |" % (
                 frac(zones_pooled["PASSIVE_PASS"], responded), frac(zones_pooled["CHALLENGE"], responded),
                 frac(zones_pooled["PAD_REJECT"], responded), zones_pooled["NO_RESPONSE"], rec_line))
    b.append("| R2 F18 volatility | share at 0.85: %s; at 0.80: %s; attack verdicts that change at 0.80: %d (newly "
             "passing: %d); bona fide PAD rejections at 0.85: %d | %s |" % (
                 frac(pooled["turned85"], pooled["passes"]), frac(pooled["turned80"], pooled["passes"]),
                 len(changes), len(newly_passing), pooled["reject85"], f18_text))
    b.append("| R3 moire cap | %s | %s |" % (", ".join("%s %s" % (s, frac(c, sc)) for s, (c, sc) in moire.items()),
                                              r3_result(flagged)))
    b.append("| R4 F15 identity | see below, verbatim | %s |" % f15_result)
    b.append("")

    b.append("## Inventory (R0)\n")
    b.append("| subject | %s | other | total | short of the run sheet |" % " | ".join(PROTOCOL))
    b.append("|---|%s---|---|---|" % ("---|" * len(PROTOCOL)))
    for s, c in inv.items():
        other = sum(v for k, v in c.items() if k not in PROTOCOL)
        gaps = protocol_gaps(c)
        b.append("| %s | %s | %d | %d | %s |" % (s, " | ".join(str(c.get(k, 0)) for k in PROTOCOL), other,
                                                 sum(c.values()), ", ".join("%s %d" % g for g in gaps.items()) or "-"))
    b.append("")

    b.append("## Bona fide per subject at passive 0.85 / reject 0.30 (R1, production)\n")
    b.append("| subject | takes | passive pass | challenge | PAD reject (BPCER) | non-response |")
    b.append("|---|---|---|---|---|---|")
    for s, z in list(zones.items()) + [("pooled", zones_pooled)]:
        r = sum(z[k] for k in ("PASSIVE_PASS", "CHALLENGE", "PAD_REJECT"))
        b.append("| %s | %d | %s | %s | %s | %d |" % (s, sum(z.values()), frac(z["PASSIVE_PASS"], r),
                                                      frac(z["CHALLENGE"], r), frac(z["PAD_REJECT"], r),
                                                      z["NO_RESPONSE"]))
    b.append("")

    b.append("## F18 volatility cap per subject (R2, recorded real-face takes)\n")
    b.append("| subject | takes | passes without guards | turned at 0.85 | turned at 0.80 | PAD reject at 0.85 | "
             "PAD reject at 0.80 | no window |")
    b.append("|---|---|---|---|---|---|---|---|")
    for s, c in list(counts.items()) + [("pooled", pooled)]:
        b.append("| %s | %d | %d | %s | %s | %d | %d | %d |" % (
            s, c["takes"], c["passes"], frac(c["turned85"], c["passes"]), frac(c["turned80"], c["passes"]),
            c["reject85"], c["reject80"], c["no_window"]))
    b.append("")
    b.append("Attack side (every recorded attack take and every attack attempt of the live logs, %d attempts): "
             "at 0.85 %s; at 0.80 %s." % (len(attack85), verdict_counts(attack85), verdict_counts(attack80)))
    if changes:
        b.append("\nVerdicts that change at 0.80 (codes only):\n")
        b += ["- %s: %s at 0.85, %s at 0.80" % c for c in changes]
    else:
        b.append("No attack verdict changes at 0.80.")
    passing = [i for i, v in attack85 if v == "PASS"]
    b.append("\nAttack attempts that pass at 0.85 (codes only): %s" % (", ".join(passing) if passing else "none"))
    b.append("")

    b.append("## Moire cap per subject (R3)\n")
    b.append("| subject | capped / scored bona fide frames | flag (> 10%) |\n|---|---|---|")
    for s, (c, sc) in moire.items():
        b.append("| %s | %s | %s |" % (s, frac(c, sc), "FLAGGED" if moire_flagged(c, sc) else "-"))
    b.append("")

    b.append("## To paste into docs/part2-iso30107-evaluation.md (PadEvaluation, verbatim)\n")
    for prefix in ("Three zones with today's settings", "Per-species APCER over thresholds, candidate 1",
                   "Recommended thresholds"):
        sec = section(pad_md, prefix)
        b.append(("#" + sec) if sec else "(no '%s' section in the PadEvaluation report)" % prefix)
        b.append("")
    b.append("## To paste into docs/part2-pad-architecture.md\n")
    b.append("F18 re-check (R2): the per-subject table above, and the decision: %s.\n" % f18_text)
    b.append(("#" + f15) if f15 else "(no F15 rule section in the BindingEvaluation report)")
    b.append("")
    return "\n".join(b), {"f18": code, "moire_flagged": flagged, "n": n}


# ---------------------------------------------------------------------------------------------------------------
# commands
# ---------------------------------------------------------------------------------------------------------------

def analyze(args, subjects):
    import session_guard_replay as sgr
    import reference_pipeline as rp

    pad, binding = Path(args.pad), Path(args.binding)
    for p in (pad / "scores.csv", pad / "frames.csv", pad / "report.md", binding / "report.md"):
        if not p.is_file():
            raise Refused("missing %s: the PadEvaluation or BindingEvaluation step did not finish" % p)
    inv = inventory(args.data, subjects)
    cfg85 = rp.guards_cfg(latch=rp.PAD_LATCH_THRESHOLD, vol=F18_DEFAULT)
    cfg80 = rp.guards_cfg(latch=rp.PAD_LATCH_THRESHOLD, vol=F18_CANDIDATE)
    bona_fide = sgr.takes_from_frames_csv(str(pad / "frames.csv"), subjects, ["bona_fide"])
    counts = f18_counts(first_attempts(bona_fide, cfg85, sgr.replay), first_attempts(bona_fide, cfg80, sgr.replay))
    attacks = [r for r in sgr.takes_from_frames_csv(str(pad / "frames.csv")) if r["species"] != "bona_fide"]
    # Every live log, the F5 second-screen series' subfolder included; discarded runs are in other files.
    logs = sorted(Path(args.sessions).rglob("sessions-*.jsonl")) if args.sessions else []
    for log in logs:
        for r in sgr.logged_runs(str(log)):
            try:
                attack = is_live_attack(r)
            except Refused as e:
                raise Refused("%s: %s" % (log.name, e))
            if attack:
                r["_log"] = log.stem
                attacks.append(r)
    md, result = summary(
        subjects, inv, bona_fide_zones(pad / "scores.csv", subjects), counts,
        all_attempts(attacks, cfg85, sgr.replay), all_attempts(attacks, cfg80, sgr.replay),
        moire_shares(pad / "frames.csv", subjects), (pad / "report.md").read_text(encoding="utf-8"),
        (binding / "report.md").read_text(encoding="utf-8"),
        ["PadEvaluation %s" % pad.name, "BindingEvaluation %s" % binding.name, "%d live log(s)" % len(logs)])
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "summary.md").write_text(md + "\n", encoding="utf-8")
    print("wrote %s" % (out / "summary.md"))
    for line in md.splitlines():
        if line.startswith("| R"):
            print(line)
    return result


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("command", nargs="?", choices=["inventory", "analyze"])
    ap.add_argument("--data", default="eval-data")
    ap.add_argument("--subjects", default="")
    ap.add_argument("--pad")
    ap.add_argument("--binding")
    ap.add_argument("--sessions")
    ap.add_argument("--out")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(SelfTest))
        return 0 if result.wasSuccessful() else 1
    subjects = [s.strip() for s in args.subjects.split(",") if s.strip()]
    try:
        if not subjects:
            raise Refused("no subjects: pass --subjects s01,s02,...")
        if args.command == "inventory":
            inv = inventory(args.data, subjects)
            if args.out:
                Path(args.out).mkdir(parents=True, exist_ok=True)
                (Path(args.out) / "inventory.json").write_text(json.dumps(inv, indent=1), encoding="utf-8")
            for s, c in inv.items():
                gaps = protocol_gaps(c)
                print("%s: %d bona fide takes, consent in every take; %s; short of the run sheet: %s" % (
                    s, sum(c.values()), ", ".join("%s %d" % kv for kv in sorted(c.items())),
                    ", ".join("%s %d" % g for g in gaps.items()) or "none"))
        elif args.command == "analyze":
            analyze(args, subjects)
        else:
            ap.error("inventory, analyze or --self-test")
    except Refused as e:
        print("REFUSED: %s" % e, file=sys.stderr)
        return 2
    return 0


# ---------------------------------------------------------------------------------------------------------------
# self-test: the rules on tiny synthetic inputs
# ---------------------------------------------------------------------------------------------------------------

class SelfTest(unittest.TestCase):

    def test_r2_share_at_25_percent_keeps_and_above_evaluates(self):
        self.assertEqual("KEEP", f18_decision(0.25, [], 3, 10)[0])
        self.assertEqual("ADOPT_080", f18_decision(0.26, [], 3, 10)[0])
        self.assertEqual("KEEP_WITH_COST", f18_decision(0.26, [("print_flat s01/1 attempt 1", "ATTACK", "CHALLENGE")],
                                                        3, 10)[0])
        self.assertIn("cost recorded: 26.0%", f18_decision(0.26, [("x", "ATTACK", "CHALLENGE")], 3, 10)[1])
        self.assertEqual("NOT_DECIDABLE", f18_decision(None, [], 3, 10)[0])

    def test_r5_fewer_than_three_subjects_is_provisional(self):
        code, text = f18_decision(0.40, [], 1, 10)
        self.assertEqual("PROVISIONAL_ADOPT_080", code)
        self.assertIn("provisional (N = 1 < 3): nothing changes", text)
        self.assertEqual("KEEP", f18_decision(0.10, [], 2, 10)[0])

    def test_r2_counts_per_subject(self):
        run = lambda s: {"subject": s}  # noqa: E731
        v85 = [(run("s01"), "PASS", "PASS"), (run("s01"), "PASS", "CHALLENGE"), (run("s02"), "CHALLENGE", "ATTACK"),
               (run("s02"), None, None)]
        v80 = [(run("s01"), "PASS", "PASS"), (run("s01"), "PASS", "PASS"), (run("s02"), "CHALLENGE", "CHALLENGE"),
               (run("s02"), None, None)]
        c = f18_counts(v85, v80)
        self.assertEqual((2, 1, 0), (c["s01"]["passes"], c["s01"]["turned85"], c["s01"]["turned80"]))
        self.assertEqual((0, 1, 0, 1), (c["s02"]["passes"], c["s02"]["reject85"], c["s02"]["reject80"],
                                        c["s02"]["no_window"]))
        self.assertIsNone(share(c["s02"]["turned85"], c["s02"]["passes"]))

    def test_r2_replay_of_a_recorded_take_at_085_and_080(self):
        # Two model scores of 0.82 early in the take: their second-lowest is below 0.85, so the volatility cap holds
        # the next frames at 0.60 and the first window is a challenge; at 0.80 the take passes, as without guards.
        import session_guard_replay as sgr
        import reference_pipeline as rp
        scores = [0.95, 0.82, 0.82] + [0.95] * 9
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "frames.csv"
            rows = ["subject,species,take,condition,frame,t_ms,faces,issues,yaw,pitch,v2,v1se,moire_cap"]
            rows += ["s01,bona_fide,1,normal,%d,%d,1,,0,0,%s,%s,0" % (i, 100 * i, s, s) for i, s in enumerate(scores)]
            rows += ["s01,print_flat,1,flat,%d,%d,1,,0,0,0.1,0.1,0" % (i, 100 * i) for i in range(12)]
            p.write_text("\n".join(rows) + "\n", encoding="utf-8")
            takes = sgr.takes_from_frames_csv(str(p), ["s01"], ["bona_fide"])
            self.assertEqual(1, len(takes))
            at85 = first_attempts(takes, rp.guards_cfg(vol=F18_DEFAULT), sgr.replay)
            at80 = first_attempts(takes, rp.guards_cfg(vol=F18_CANDIDATE), sgr.replay)
            self.assertEqual(("PASS", "CHALLENGE"), at85[0][1:])
            self.assertEqual(("PASS", "PASS"), at80[0][1:])
            c = f18_counts(at85, at80)["s01"]
            self.assertEqual((1, 1, 0), (c["passes"], c["turned85"], c["turned80"]))
            attack = [t for t in sgr.takes_from_frames_csv(str(p)) if t["species"] != "bona_fide"]
            self.assertEqual([("recorded print_flat s01/1 attempt 1", "ATTACK")],
                             all_attempts(attack, rp.guards_cfg(vol=F18_CANDIDATE), sgr.replay))

    def test_r2_live_attack_cases_from_the_logged_case(self):
        self.assertEqual(("a", "zf", "y"), (case_code("a14"), case_code("zf6"), case_code("y4")))
        for real in ("a1", "b2", "c3", "h4", "i5", "j6", "k7", "l9", "m3"):
            self.assertFalse(is_live_attack({"case": real}), real)
        for attack in ("d1", "e2", "f3", "g4", "n5", "o6", "p7", "q8", "r9", "s1", "t2", "u13", "v14", "w10", "x12",
                       "y1", "z5", "zf6", "z"):
            self.assertTrue(is_live_attack({"case": attack}), attack)

    def test_r2_a_case_outside_sessiondemo_stops_the_run(self):
        # A run of a case the rules do not list is neither real face nor attack: counting it as an attack would put
        # it on the attack side of R2 (task 19 review).
        for case in ("zz3", "A1", "", None):
            with self.assertRaisesRegex(Refused, "unknown SessionDemo case"):
                is_live_attack({"case": case})

    def test_r2_without_an_attack_attempt_0_80_is_not_adopted(self):
        # "No attack verdict changes" needs attack attempts to replay (task 19 review).
        code, text = f18_decision(0.40, [], 3, 0)
        self.assertEqual("NOT_DECIDABLE", code)
        self.assertIn("no attack attempt with a passive window to replay", text)
        self.assertEqual("KEEP", f18_decision(0.20, [], 3, 0)[0])
        md, result = summary(["s01", "s02", "s03"], {}, {}, {"s01": collections.Counter(passes=5, turned85=2)},
                             [("y s01 1 attempt 1", None)], [("y s01 1 attempt 1", None)], {}, "", "", [])
        self.assertEqual("NOT_DECIDABLE", result["f18"])

    def test_r5_a_subject_listed_twice_is_refused(self):
        # N counts distinct people: s01 listed three times is one subject, not the three that R5 needs.
        with tempfile.TemporaryDirectory() as d:
            self._take(d, "s01", 1)
            with self.assertRaisesRegex(Refused, "listed more than once: s01"):
                inventory(d, ["s01", "s01", "s01"])

    def test_r3_flag_is_strictly_above_10_percent_and_report_only(self):
        self.assertFalse(moire_flagged(10, 100))
        self.assertTrue(moire_flagged(11, 100))
        self.assertFalse(moire_flagged(0, 0))
        self.assertEqual("no subject flagged", r3_result([]))
        self.assertEqual("flagged: s02, s03; report only: into the limitations of docs/part2-iso30107-evaluation.md, "
                         "the moire cap does not change this week", r3_result(["s02", "s03"]))

    def test_r3_shares_from_frames_csv(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "frames.csv"
            p.write_text("subject,species,take,condition,frame,t_ms,faces,issues,yaw,pitch,v2,v1se,moire_cap\n"
                         "s01,bona_fide,1,normal,0,0,1,,0,0,0.9,0.9,1\n"
                         "s01,bona_fide,1,normal,1,100,1,,0,0,0.9,0.9,0\n"
                         "s01,bona_fide,1,normal,2,200,0,,,,,,0\n"
                         "s01,print_flat,1,flat,0,0,1,,0,0,0.1,0.1,1\n", encoding="utf-8")
            self.assertEqual([1, 2], moire_shares(p, ["s01"])["s01"])

    def _take(self, root, subject, take, consent=True, condition="normal", meta_subject=None):
        t = Path(root) / "bona_fide" / subject / str(take)
        t.mkdir(parents=True)
        (t / "meta.json").write_text(json.dumps({"subject": meta_subject or subject, "condition": condition,
                                                 "consent": consent}), encoding="utf-8")

    def test_r0_consent_refuses_not_skips(self):
        with tempfile.TemporaryDirectory() as d:
            self._take(d, "s01", 1)
            self._take(d, "s02", 1, consent=False)
            with self.assertRaisesRegex(Refused, "s02: no consent marker in take\\(s\\) 1. The subject is refused"):
                inventory(d, ["s01", "s02"])

    def test_r0_unlisted_subjects_names_and_empty_subjects_stop(self):
        with tempfile.TemporaryDirectory() as d:
            self._take(d, "s01", 1)
            self._take(d, "s02", 1, condition="dim")
            with self.assertRaisesRegex(Refused, "not listed: s02"):
                inventory(d, ["s01"])
            with self.assertRaisesRegex(Refused, "codes only"):
                inventory(d, ["s01", "Alice"])
            with self.assertRaisesRegex(Refused, "s03: no bona fide takes"):
                inventory(d, ["s01", "s02", "s03"])
            inv = inventory(d, ["s01", "s02"])
            self.assertEqual({"normal": 1}, inv["s01"])
            self.assertEqual(2, protocol_gaps(inv["s01"])["normal"])

    def test_sections_and_summary_carry_no_paths(self):
        pad = ("# x\n## Three zones with today's settings (passive 0.85)\n\n| a |\n## Per-species APCER over "
               "thresholds, candidate 1\n\nrow\n## Recommended thresholds (provisional)\n\n- Candidate 1 (MEAN): "
               "passiveThreshold **0.670**, padRejectThreshold **0.550**\n")
        binding = "## F15 identity threshold rule\n\n**Result: not decidable: 1 person / 11 pairs** x\n"
        self.assertEqual("## Recommended thresholds (provisional)\n\n- Candidate 1 (MEAN): passiveThreshold **0.670**,"
                         " padRejectThreshold **0.550**", section(pad, "Recommended thresholds"))
        counts = {"s01": collections.Counter(takes=11, passes=6, turned85=1, turned80=0, no_window=3)}
        md, result = summary(["s01"], {"s01": {"normal": 3}}, {"s01": collections.Counter(PASSIVE_PASS=6)}, counts,
                             [("print_flat s01/1 attempt 1", "ATTACK")], [("print_flat s01/1 attempt 1", "ATTACK")],
                             {"s01": (1, 50)}, pad, binding, ["PadEvaluation pad"])
        self.assertIn("N = 1 subject (s01): not decidable / provisional where the rules need 3", md)
        self.assertIn("| R2 F18 volatility | share at 0.85: 1/6 (16.7%)", md)
        self.assertIn("keep 0.85 (share 16.7% at or below 25%); provisional (N = 1 < 3)", md)
        self.assertIn("| R4 F15 identity | see below, verbatim | Result: not decidable: 1 person / 11 pairs x |", md)
        self.assertIn(RULES, md)
        self.assertNotRegex(md, r"[\\/]frame_|eval-data[\\/]|\.jpg")
        self.assertEqual("KEEP", result["f18"])

    def test_the_runner_carries_the_rules_verbatim(self):
        ps1 = Path(__file__).resolve().parents[2] / "scripts" / "f9-reeval.ps1"
        text = ps1.read_text(encoding="utf-8")
        block = text.split("# RULES BEGIN\n", 1)[1].split("# RULES END", 1)[0]
        self.assertEqual(RULES, "".join(line[2:] + "\n" if line.startswith("# ") else "\n"
                                        for line in block.splitlines()))


if __name__ == "__main__":
    sys.exit(main())
