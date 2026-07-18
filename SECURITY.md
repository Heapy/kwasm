# Security policy

kwasm parses and executes attacker-controlled WebAssembly and snapshot bytes,
so security work is part of the runtime's core correctness. The project is
still pre-release: this policy describes the intended response process and
deployment boundary, not a certification or warranty.

## Supported versions

| Version | Security updates | API/format stability |
|---|---|---|
| Unreleased `main` / `0.1.0-SNAPSHOT` | Best effort | No compatibility guarantee |
| Historical development commits | No | None |
| Published stable releases | None exist yet | Not applicable |

When the first release is published, this table will list the maintained
minor lines and their support end dates. Until then, reporters should reproduce
issues against the current default branch whenever possible.

## Report a vulnerability

Do not open a public issue for a suspected vulnerability. Use GitHub's private
vulnerability-reporting form:

<https://github.com/heapy/kwasm/security/advisories/new>

Include:

- the affected commit or version and platform;
- a minimal module, snapshot, or test case;
- required runtime, store, WASI, and snapshot configuration;
- observed impact and whether it crosses a configured capability or limit;
- a stack trace, trap, or resource profile with secrets removed;
- any proposed disclosure deadline.

If private vulnerability reporting is unavailable, contact a repository owner
privately and ask for a secure channel without sending exploit details first.
Maintainers should acknowledge a complete report promptly, coordinate a fix
and regression test, and publish an advisory after affected users have a
reasonable upgrade window.

## Threat model

The guest module and snapshot bytes are untrusted. The embedder, configured
host imports, and process supervisor are trusted.

Security-sensitive runtime properties include:

- total, bounded decoding and validation of claimed lengths and counts;
- memory/table bounds checks and type-safe indirect calls;
- enforcement of module, frame, value-stack, memory, table, and snapshot
  ceilings;
- cancellation and fuel behavior that cannot be caught by guest code;
- WASI preopen confinement and rejection of `..`, absolute-path, and symlink
  escapes;
- exact-module hash verification before snapshot restoration;
- rejection of unsupported instructions during validation rather than during
  execution;
- no ambient authority when the embedder supplies no imports.

Good vulnerability reports include limit bypasses, hangs on bounded input,
unexpected host exceptions reachable from malformed input, capability escapes,
snapshot state corruption, type confusion, and divergences that permit a guest
to access state outside its instance.

Host functions are trusted native Kotlin code. Their blocking, allocation,
network, filesystem, and exception behavior is outside the VM sandbox unless
the embedder constrains it. An import that exposes a secret or ambient
filesystem access grants that authority to the guest by design.

## Resource exhaustion and process isolation

Fuel, validation limits, stack limits, and coroutine deadlines reduce denial
of service, but they are cooperative in-process controls. They do not form a
hard CPU, memory, or multi-tenant boundary:

- checkpoints occur at defined VM safe points, not at every host CPU cycle;
- allocations for module/runtime state consume the containing process;
- a trusted host import can block a thread, ignore cancellation, or allocate
  without VM accounting;
- an implementation defect can crash or stall the application even when it
  cannot corrupt native memory directly.

Run hostile or mutually untrusted tenants in separate disposable processes or
containers. The parent supervisor, not the guest worker, should:

1. enforce CPU quotas and a hard wall-clock termination deadline;
2. cap address space/resident memory, file descriptors, threads/processes, and
   output size;
3. start from an empty environment and working directory with no inherited
   secrets or privileged handles;
4. disable network access unless a narrowly scoped proxy is required;
5. mount only immutable inputs and a bounded scratch directory;
6. communicate over a length-bounded, authenticated protocol;
7. discard the worker after cancellation, poison, trap-policy violation, or
   abnormal termination.

Fuel and coroutine cancellation should still be enabled inside that worker to
provide graceful scheduling and diagnostics before the supervisor's hard kill.

## Embedding checklist

- Set `ModuleValidationLimits` explicitly for the expected workload rather
  than accepting maximum defaults.
- Use `ExecutionLimits`, fuel, and an appropriately short checkpoint interval.
- Apply a deadline around every invocation and discard a poisoned store.
- Resolve imports by an allowlist and verify their exact `FuncType`.
- Treat host exceptions as host failures; do not expose stack traces or
  credentials to the guest.
- Give WASI an empty configuration by default. Add only required preopens,
  rights, arguments, environment entries, streams, clock, and random source.
- Bound input/output buffers and host-import concurrency independently.
- Avoid sharing mutable host objects across tenants through imports or
  `externref`.
- Keep the runtime and Kotlin/coroutines dependencies current.

## Snapshot handling

The snapshot format provides structural validation and exact-module binding.
It does **not** provide integrity, authenticity, confidentiality, replay
protection, or rollback protection.

- Authenticate snapshots before decoding/restoring them; encrypt them when
  state is sensitive.
- Bind authenticated metadata to the tenant, module identity, application
  version, monotonic sequence number, and limits profile.
- Use conservative `SnapshotLimits` even for authenticated storage.
- Never restore a snapshot into a store/module/configuration chosen only from
  unauthenticated snapshot metadata.
- Implement `SnapshotHooks` as a strict allowlist. Rehydration keys are
  attacker-controlled input and must not become arbitrary object IDs, paths,
  SQL, or network destinations.
- Rotate or invalidate snapshots when host-import semantics or secrets change.
- Keep snapshots out of logs, crash reports, and public CI artifacts.

## WASI filesystem guidance

The common in-memory filesystem supports relative symbolic links, but resolves
them only within the granted directory, limits link traversal, and rejects
absolute guest paths or link targets and any parent traversal above that
capability. The JVM real-filesystem backend canonicalizes its preopen root,
normalizes guest `.`/`..` components without allowing an escape, and refuses
symlink resolution outside the root, including create-through-link cases. The
Apple/Linux Native backend resolves parent components through
descriptor-relative `openat` with `O_NOFOLLOW`. It may create, inspect, read,
or remove a link itself, but deliberately refuses requests to follow one.

Canonical-path checks do not eliminate all filesystem races. A privileged
host process must still account for platform-specific normalization, alternate
data streams, case folding, mount points, and time-of-check/time-of-use
replacement. Prefer a dedicated, minimally privileged directory or an
OS-level sandbox for hostile guests; do not rely on string-prefix checks.

## Dependency and CI policy

Pull requests run focused JVM tests plus target-family compilation/execution
jobs. CodeQL scans JVM-compiled Kotlin sources. Gradle and GitHub Actions
dependencies are monitored by Dependabot.

CI is evidence, not proof. Changes to decoding, validation, bounds arithmetic,
snapshot parsing, linking, WASI paths/rights, fuel, or cancellation require a
negative regression test. Exclusions from upstream conformance suites must be
checked in with a public tracking issue; silent skips are not accepted.
