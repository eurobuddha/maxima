# maxima — working rules

## Versioning guardrail — every code change ships with a version bump

Real funds, real chain. NEVER change code without bumping the version
(versionCode + versionName in app/build.gradle), so every committed state is distinct, reversible and trackable. One
logical change = one version = one commit = one push, in order. Enforced by a
pre-commit hook (.githooks/pre-commit, install once: sh .githooks/install.sh)
that blocks a code change with no version bump. Do NOT bypass with --no-verify.
Docs/config-only commits need no bump.
