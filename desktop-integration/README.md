# Part 4: Desktop integration patch

`person4-desktop.diff` is every Part 4 change as one diff, relative to `registration-client/registration` at
v1.2.0.2 with this repository (Parts 1, 2, 3 and 5) copied over it. It changes 63 text files: new liveness classes,
tests and docs, the liveness block appended to the Arabic and French message bundles, two tile titles appended to the
label bundles, plus small additive hooks in existing client classes and the home page's operational tasks (offline
model install and rollback, F17). The 14 screen images in `docs/images/part4/` are binary and are shipped as files, not in the
diff.

Apply, from `registration-client/registration` after copying this repository over it:

    git apply desktop-integration/person4-desktop.diff

Or copy this repository over `registration/` (it already contains the changed files). Then build and test as in
[docs/part4-desktop-integration.md](../docs/part4-desktop-integration.md) §10.
