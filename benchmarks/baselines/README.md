# Performance baselines

No measured score is checked in here yet.

The performance workflow restores a previous same-target normalized result
from the default branch, compares the current run, and saves the new result
only after a successful `main` run. That avoids presenting a developer
laptop result as a stable GitHub runner baseline.

`external-unmeasured.json` is deliberately an empty, machine-readable state.
It must not be interpreted as a passing Chasm or Chicory comparison. Add
records only after running the pinned commits in `../upstreams.lock.json` on
the same machine and under the same harness settings as kwasm. Every record
must satisfy `external-comparisons.schema.json`.

Wasmtime/Cranelift evidence is deliberately separate and informational. A
generated `compiled-reference-<target>.json` must satisfy
`compiled-reference.schema.json`; it is not an external-comparison record or
an input to a performance gate.
