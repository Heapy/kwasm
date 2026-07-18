# kwasm WASI Preview 1

The module provides three explicit filesystem capabilities:

- `InMemoryFileSystem` is available from `commonMain` and implements regular
  files, directories, hard links, symbolic links, stable inode identities, and
  deterministic nonzero timestamps.
- `JvmFileSystem` exposes a JVM host directory, canonicalizes paths, and
  rejects traversal or symlink escape from its preopen root.
- `NativeFileSystem` exposes a Linux or Apple host directory. It resolves each
  guest path component with descriptor-relative `openat(2)` and `O_NOFOLLOW`,
  so concurrent path replacement cannot redirect a lookup outside the preopen.

`NativeFileSystem` can create, inspect, and remove a symbolic link, but it
intentionally never follows one, including for a `path_open` request carrying
`LOOKUP_SYMLINK_FOLLOW`. Linux `openat2(2)` can express “follow, but remain
beneath”, but there is no equivalent portable across the supported Apple
targets. Failing closed gives the same confinement guarantee everywhere.

The Preview 1 path family includes directory creation, path filestat and
timestamp updates, hard links, readlink, directory removal, rename, symlink,
and file unlink. Guest `..` components are normalized only while they remain
beneath the granted directory. Absolute guest paths, absolute symlink targets,
and attempts to climb above the capability root fail with `NOTCAPABLE`.
File-creating and file-removing operations reject trailing-slash destinations
with `NOTDIR`.

Native path operations remain descriptor-relative (`*at` APIs) and do not
follow the final link. Native filestat/timestamp requests that would follow a
final symlink fail closed with `NOTCAPABLE`; Native hard-link requests with
`LOOKUP_SYMLINK_FOLLOW` report `NOTSUP`.

The Native backend owns one descriptor for its preopen root. Call its
suspending `close()` after the owning WASI instance is no longer used. Child
directory capabilities then fail with `BADF`; regular files already opened by
WASI own independent descriptors and remain valid until `fd_close`.

These capabilities constrain path traversal, not the host directory's
contents. Use a minimally privileged directory and an OS process sandbox when
untrusted host users can rename mount points or modify the preopen tree.

WASI descriptor snapshots retain each numeric descriptor and resource kind,
reduced rights, flags, file offset, preopen metadata, and `fd_renumber`
effects. Streams, directory capabilities, and file handles remain host-owned:
snapshot callers must supply `WasiSnapshotResourceHooks` keys and restore into
a fresh, pristine `WasiPreview1`.
