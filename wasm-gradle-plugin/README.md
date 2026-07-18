# kwasm Gradle plugin development reload

The plugin wires the selected Kotlin/Wasm guest build, generated bindgen
sources, the embedded host resource, and a tracked reload-manifest task.

```kotlin
plugins {
    id("io.heapy.kwasm")
}

kwasm {
    guestProject.set(":guest")
    devMode.set(true)
}
```

Run the development lifecycle through Gradle's supported continuous-build
mode:

```shell
./gradlew kwasmDevReload --continuous
```

`kwasmDevReload` directly declares the linked `kwasm.guestPath` `.wasm` as an
input. Its dependency graph also contains `embedKwasmGuest` and the selected
guest compilation task, so a guest source change rebuilds the executable while
an artifact change is sufficient to trigger a new publication.

Gradle owns file-system watching, rebuild scheduling, cancellation, and event
coalescing. Its continuous-build quiet period defaults to 250 ms. It can be
changed for a run without altering the plugin model:

```shell
./gradlew kwasmDevReload --continuous \
  -Dorg.gradle.continuous.quietperiod=500
```

Cancel with the normal Gradle mechanisms (Ctrl-C, Ctrl-D in an interactive
continuous build, or a Tooling API cancellation token). The plugin creates no
daemon thread or build service that can survive the build.

## Publication contract

`embedKwasmGuest` copies a stable source snapshot into the generated resource.
If the source metadata changes during the copy, the temporary file is
discarded and the previous resource remains visible. Gradle observes the input
change and a continuous build schedules another iteration after the quiet
period.

After the resource task completes, `kwasmDevReload` atomically replaces the
manifest:

```properties
kwasm-reload-format=1
resource=kwasm/guest.wasm
size=8
sha256=93a44bbb96c751218e4c00d479e4c14356e8780fcc1d0bd1c33a9e1a2136f3e6
```

Consumers should react to the manifest change and then load the resource. The
manifest is written last and is the commit marker for that resource snapshot.
Consumers should verify the declared size and SHA-256 and retry when they do
not match: a later reload can replace the resource after an earlier manifest
was observed but before that resource was opened.
Both files use a uniquely named temporary sibling followed by
`ATOMIC_MOVE`. If the output file system rejects atomic moves, the plugin falls
back to a regular same-directory replacement; strict atomic visibility then
depends on that file system.

Source stability is checked with file identity, size, and last-modified time.
A producer that mutates a file in place while preserving all of that metadata
can evade the check; guest producers should prefer temp-file-plus-rename.

The one-shot task remains cacheable and configuration-cache compatible.
Without `--continuous`, it performs one publication and exits; file watching
and cancellation semantics apply only when Gradle continuous mode is enabled.
