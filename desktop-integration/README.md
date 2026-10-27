# Part 4: Desktop integration patch

`person4-desktop.diff` is every Part 4 change as one diff, relative to `registration-client/registration` at
v1.2.0.2 with this repository (Parts 1, 2, 3 and 5) copied over it. It changes 67 text files:
- new liveness classes, tests and docs;
- the liveness block appended to the Arabic and French message bundles, and two tile titles appended to the label
  bundles;
- small additive hooks in existing client classes: the home page's operational tasks (offline model install and
  rollback, F17), and the password login's identity check (F15).

The `registration-services` pom section and the root README section hold Part 4's changes only; Part 1's own
additions there (the OpenCV dependency of F13b, its README rows) come with the repository copy.

The 14 screen images in `docs/images/part4/` are binary and are shipped as files, not in the diff.

Apply, from `registration-client/registration` after copying this repository over it:

    git apply desktop-integration/person4-desktop.diff

Or copy this repository over `registration/` (it already contains the changed files). Then build and test as in
[docs/part4-desktop-integration.md](../docs/part4-desktop-integration.md) §10.
