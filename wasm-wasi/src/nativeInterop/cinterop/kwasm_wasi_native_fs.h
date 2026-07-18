#ifndef KWASM_WASI_NATIVE_FS_H
#define KWASM_WASI_NATIVE_FS_H

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

/*
 * Kotlin/Native's bundled POSIX interop intentionally omits the variadic
 * openat(2). These non-variadic wrappers keep every guest path lookup anchored
 * to a directory descriptor.
 */
static inline int kwasm_wasi_open_root(const char *path) {
    return open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
}

static inline int kwasm_wasi_open_directory_at(int directory, const char *name) {
    int descriptor = openat(
        directory,
        name,
        O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC
    );
    if (descriptor < 0 && errno == ENOTDIR) {
        /*
         * Darwin reports ENOTDIR, rather than ELOOP, when O_DIRECTORY and
         * O_NOFOLLOW encounter a symlink. Normalize that case so Kotlin can
         * distinguish a denied link from an ordinary non-directory.
         */
        int original_error = errno;
        struct stat status;
        if (fstatat(directory, name, &status, AT_SYMLINK_NOFOLLOW) == 0 &&
            S_ISLNK(status.st_mode)) {
            errno = ELOOP;
        } else {
            errno = original_error;
        }
    }
    return descriptor;
}

static inline int kwasm_wasi_open_file_at(
    int directory,
    const char *name,
    int readable,
    int writable,
    int create,
    int exclusive,
    int truncate
) {
    int access_mode;
    if (readable && writable) {
        access_mode = O_RDWR;
    } else if (writable || truncate) {
        access_mode = O_WRONLY;
    } else {
        access_mode = O_RDONLY;
    }

    int flags = access_mode | O_NOFOLLOW | O_CLOEXEC;
    if (create) {
        flags |= O_CREAT;
    }
    if (exclusive) {
        flags |= O_EXCL;
    }
    if (truncate) {
        flags |= O_TRUNC;
    }
    return openat(directory, name, flags, (mode_t)0600);
}

/* 1 = regular file, 2 = directory, 0 = another file type, -1 = fstat error. */
static inline int kwasm_wasi_fd_kind(int descriptor) {
    struct stat status;
    if (fstat(descriptor, &status) != 0) {
        return -1;
    }
    if (S_ISREG(status.st_mode)) {
        return 1;
    }
    if (S_ISDIR(status.st_mode)) {
        return 2;
    }
    return 0;
}

static inline int64_t kwasm_wasi_fd_size(int descriptor) {
    struct stat status;
    if (fstat(descriptor, &status) != 0) {
        return -1;
    }
    return (int64_t)status.st_size;
}

static inline int64_t kwasm_wasi_pread(
    int descriptor,
    void *buffer,
    size_t count,
    int64_t offset
) {
    return (int64_t)pread(descriptor, buffer, count, (off_t)offset);
}

static inline int64_t kwasm_wasi_pwrite(
    int descriptor,
    const void *buffer,
    size_t count,
    int64_t offset
) {
    return (int64_t)pwrite(descriptor, buffer, count, (off_t)offset);
}

/*
 * Descriptor-only metadata and mutation helpers. Keeping these operations on
 * the already-open descriptor preserves Preview 1's fd semantics across
 * renames and avoids reopening ambient host paths.
 */
static inline uint64_t kwasm_wasi_timespec_nanos(time_t seconds, long nanos) {
    if (seconds < 0 || nanos < 0) {
        return 0;
    }
    uint64_t unsigned_seconds = (uint64_t)seconds;
    if (unsigned_seconds > (UINT64_MAX - (uint64_t)nanos) / UINT64_C(1000000000)) {
        return UINT64_MAX;
    }
    return unsigned_seconds * UINT64_C(1000000000) + (uint64_t)nanos;
}

/*
 * values: device, inode, link count, size, access time, modification time,
 * status-change time.
 */
static inline int kwasm_wasi_fd_stat(int descriptor, uint64_t *values) {
    struct stat status;
    if (fstat(descriptor, &status) != 0) {
        return -1;
    }
    values[0] = (uint64_t)status.st_dev;
    values[1] = (uint64_t)status.st_ino;
    values[2] = (uint64_t)status.st_nlink;
    values[3] = status.st_size < 0 ? 0 : (uint64_t)status.st_size;
#if defined(__APPLE__)
    values[4] = kwasm_wasi_timespec_nanos(
        status.st_atimespec.tv_sec,
        status.st_atimespec.tv_nsec
    );
    values[5] = kwasm_wasi_timespec_nanos(
        status.st_mtimespec.tv_sec,
        status.st_mtimespec.tv_nsec
    );
    values[6] = kwasm_wasi_timespec_nanos(
        status.st_ctimespec.tv_sec,
        status.st_ctimespec.tv_nsec
    );
#else
    values[4] = kwasm_wasi_timespec_nanos(
        status.st_atim.tv_sec,
        status.st_atim.tv_nsec
    );
    values[5] = kwasm_wasi_timespec_nanos(
        status.st_mtim.tv_sec,
        status.st_mtim.tv_nsec
    );
    values[6] = kwasm_wasi_timespec_nanos(
        status.st_ctim.tv_sec,
        status.st_ctim.tv_nsec
    );
#endif
    return 0;
}

static inline int kwasm_wasi_fd_truncate(int descriptor, int64_t size) {
    return ftruncate(descriptor, (off_t)size);
}

static inline int kwasm_wasi_fd_sync(int descriptor) {
    return fsync(descriptor);
}

static inline int kwasm_wasi_fd_datasync(int descriptor) {
#if defined(__APPLE__)
    /*
     * Darwin does not expose fdatasync(2). fsync(2) is the safe, stronger
     * implementation of Preview 1's data-only durability guarantee.
     */
    return fsync(descriptor);
#else
    return fdatasync(descriptor);
#endif
}

static inline int kwasm_wasi_nanos_to_timespec(
    uint64_t nanos,
    struct timespec *result
) {
    uint64_t seconds = nanos / UINT64_C(1000000000);
    time_t converted_seconds = (time_t)seconds;
    if (converted_seconds < 0 || (uint64_t)converted_seconds != seconds) {
        errno = EOVERFLOW;
        return -1;
    }
    result->tv_sec = converted_seconds;
    result->tv_nsec = (long)(nanos % UINT64_C(1000000000));
    return 0;
}

static inline int kwasm_wasi_fd_set_times(
    int descriptor,
    int set_access_time,
    uint64_t access_time,
    int set_modification_time,
    uint64_t modification_time
) {
    struct timespec times[2];
    if (set_access_time) {
        if (kwasm_wasi_nanos_to_timespec(access_time, &times[0]) != 0) {
            return -1;
        }
    } else {
        times[0].tv_sec = 0;
        times[0].tv_nsec = UTIME_OMIT;
    }
    if (set_modification_time) {
        if (kwasm_wasi_nanos_to_timespec(modification_time, &times[1]) != 0) {
            return -1;
        }
    } else {
        times[1].tv_sec = 0;
        times[1].tv_nsec = UTIME_OMIT;
    }
    return futimens(descriptor, times);
}

/*
 * Descriptor-relative path operations. Kotlin opens every parent directory
 * one component at a time with O_NOFOLLOW before passing the final basename to
 * these wrappers, so no operation gains ambient path authority.
 */
static inline int kwasm_wasi_path_create_directory(
    int directory,
    const char *name
) {
    return mkdirat(directory, name, (mode_t)0700);
}

/*
 * values: kind, device, inode, link count, size, access time, modification
 * time, status-change time. This deliberately does not follow the final link.
 */
static inline int kwasm_wasi_path_stat(
    int directory,
    const char *name,
    uint64_t *values
) {
    struct stat status;
    if (fstatat(directory, name, &status, AT_SYMLINK_NOFOLLOW) != 0) {
        return -1;
    }
    if (S_ISREG(status.st_mode)) {
        values[0] = 1;
    } else if (S_ISDIR(status.st_mode)) {
        values[0] = 2;
    } else if (S_ISLNK(status.st_mode)) {
        values[0] = 3;
    } else {
        values[0] = 0;
    }
    values[1] = (uint64_t)status.st_dev;
    values[2] = (uint64_t)status.st_ino;
    values[3] = (uint64_t)status.st_nlink;
    values[4] = status.st_size < 0 ? 0 : (uint64_t)status.st_size;
#if defined(__APPLE__)
    values[5] = kwasm_wasi_timespec_nanos(
        status.st_atimespec.tv_sec,
        status.st_atimespec.tv_nsec
    );
    values[6] = kwasm_wasi_timespec_nanos(
        status.st_mtimespec.tv_sec,
        status.st_mtimespec.tv_nsec
    );
    values[7] = kwasm_wasi_timespec_nanos(
        status.st_ctimespec.tv_sec,
        status.st_ctimespec.tv_nsec
    );
#else
    values[5] = kwasm_wasi_timespec_nanos(
        status.st_atim.tv_sec,
        status.st_atim.tv_nsec
    );
    values[6] = kwasm_wasi_timespec_nanos(
        status.st_mtim.tv_sec,
        status.st_mtim.tv_nsec
    );
    values[7] = kwasm_wasi_timespec_nanos(
        status.st_ctim.tv_sec,
        status.st_ctim.tv_nsec
    );
#endif
    return 0;
}

static inline int kwasm_wasi_path_set_times(
    int directory,
    const char *name,
    int set_access_time,
    uint64_t access_time,
    int set_modification_time,
    uint64_t modification_time
) {
    struct timespec times[2];
    if (set_access_time) {
        if (kwasm_wasi_nanos_to_timespec(access_time, &times[0]) != 0) {
            return -1;
        }
    } else {
        times[0].tv_sec = 0;
        times[0].tv_nsec = UTIME_OMIT;
    }
    if (set_modification_time) {
        if (kwasm_wasi_nanos_to_timespec(modification_time, &times[1]) != 0) {
            return -1;
        }
    } else {
        times[1].tv_sec = 0;
        times[1].tv_nsec = UTIME_OMIT;
    }
    return utimensat(
        directory,
        name,
        times,
        AT_SYMLINK_NOFOLLOW
    );
}

static inline int kwasm_wasi_path_link(
    int source_directory,
    const char *source_name,
    int target_directory,
    const char *target_name
) {
    return linkat(
        source_directory,
        source_name,
        target_directory,
        target_name,
        0
    );
}

static inline int64_t kwasm_wasi_path_readlink(
    int directory,
    const char *name,
    void *buffer,
    size_t size
) {
    return (int64_t)readlinkat(directory, name, (char *)buffer, size);
}

static inline int kwasm_wasi_path_remove_directory(
    int directory,
    const char *name
) {
    return unlinkat(directory, name, AT_REMOVEDIR);
}

static inline int kwasm_wasi_path_rename(
    int source_directory,
    const char *source_name,
    int target_directory,
    const char *target_name
) {
    return renameat(
        source_directory,
        source_name,
        target_directory,
        target_name
    );
}

static inline int kwasm_wasi_path_symlink(
    const char *target,
    int directory,
    const char *name
) {
    return symlinkat(target, directory, name);
}

static inline int kwasm_wasi_path_unlink_file(
    int directory,
    const char *name
) {
    return unlinkat(directory, name, 0);
}

/*
 * Read a bounded batch of direct children without transferring ownership of,
 * closing, or advancing the capability descriptor. openat(".") creates an
 * independent open file description; fdopendir owns only that new descriptor.
 *
 * Each record occupies four uint64_t values: inode, name offset, name length,
 * and kind (1 regular, 2 directory, 3 symlink, 0 other). Result values are
 * record count, name bytes used, and whether end-of-directory was reached.
 */
static inline int kwasm_wasi_directory_read_batch(
    int descriptor,
    uint64_t start_index,
    uint64_t *records,
    size_t record_capacity,
    char *names,
    size_t name_capacity,
    uint64_t *result
) {
    int enumeration_descriptor = openat(
        descriptor,
        ".",
        O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC
    );
    if (enumeration_descriptor < 0) {
        return -1;
    }
    DIR *stream = fdopendir(enumeration_descriptor);
    if (stream == NULL) {
        int original_error = errno;
        close(enumeration_descriptor);
        errno = original_error;
        return -1;
    }

    uint64_t skipped = 0;
    size_t record_count = 0;
    size_t name_bytes = 0;
    int complete = 0;
    int read_error = 0;
    while (1) {
        errno = 0;
        struct dirent *entry = readdir(stream);
        if (entry == NULL) {
            read_error = errno;
            complete = read_error == 0;
            break;
        }
        if (
            strcmp(entry->d_name, ".") == 0 ||
            strcmp(entry->d_name, "..") == 0
        ) {
            continue;
        }
        if (skipped < start_index) {
            skipped++;
            continue;
        }

        size_t name_length = strlen(entry->d_name);
        if (
            record_count >= record_capacity ||
            name_length > name_capacity - name_bytes
        ) {
            if (record_count == 0 && name_length > name_capacity) {
                read_error = ENAMETOOLONG;
            }
            break;
        }

        uint64_t kind = 0;
#if defined(DT_REG)
        if (entry->d_type == DT_REG) {
            kind = 1;
        } else if (entry->d_type == DT_DIR) {
            kind = 2;
        } else if (entry->d_type == DT_LNK) {
            kind = 3;
        } else if (entry->d_type == DT_UNKNOWN) {
            struct stat status;
            if (
                fstatat(
                    descriptor,
                    entry->d_name,
                    &status,
                    AT_SYMLINK_NOFOLLOW
                ) == 0
            ) {
                if (S_ISREG(status.st_mode)) {
                    kind = 1;
                } else if (S_ISDIR(status.st_mode)) {
                    kind = 2;
                } else if (S_ISLNK(status.st_mode)) {
                    kind = 3;
                }
            }
        }
#endif

        size_t record_offset = record_count * 4;
        records[record_offset] = (uint64_t)entry->d_ino;
        records[record_offset + 1] = (uint64_t)name_bytes;
        records[record_offset + 2] = (uint64_t)name_length;
        records[record_offset + 3] = kind;
        memcpy(names + name_bytes, entry->d_name, name_length);
        name_bytes += name_length;
        record_count++;
    }

    int close_result = closedir(stream);
    if (read_error == 0 && close_result != 0) {
        read_error = errno;
    }
    if (read_error != 0) {
        errno = read_error;
        return -1;
    }
    result[0] = (uint64_t)record_count;
    result[1] = (uint64_t)name_bytes;
    result[2] = (uint64_t)complete;
    return 0;
}

static inline int kwasm_wasi_close(int descriptor) {
    return close(descriptor);
}

static inline int kwasm_wasi_errno(void) {
    return errno;
}

#endif
