package io.heapy.kwasm.gradle

import org.gradle.api.GradleException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * File publication shared by the embed and reload-manifest tasks.
 *
 * The embedded resource is replaced first and the manifest is replaced last.
 * Consumers can consequently use a manifest change as the commit marker for a
 * complete resource snapshot.
 */
internal object KwasmReloadFiles {
    internal fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).buffered().use { input ->
            val buffer = ByteArray(DIGEST_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHexString()
    }

    internal fun manifestContent(
        embeddedResourcePath: String,
        size: Long,
        sha256: String,
    ): String =
        buildString {
            appendLine("kwasm-reload-format=1")
            appendLine("resource=$embeddedResourcePath")
            appendLine("size=$size")
            appendLine("sha256=$sha256")
        }

    internal fun validateResourcePath(resourcePath: String) {
        val normalized = resourcePath.replace('\\', '/')
        if (
            normalized.isBlank() ||
            normalized.startsWith('/') ||
            normalized.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
            '\n' in normalized ||
            '\r' in normalized
        ) {
            throw GradleException(
                "kwasm.embeddedResourcePath must be a safe relative resource path: " +
                    resourcePath,
            )
        }
    }

    internal fun writeManifestAtomically(
        output: Path,
        content: String,
    ) {
        Files.createDirectories(output.parent)
        if (Files.exists(output) && Files.readString(output) == content) return

        val temporaryOutput = temporarySibling(output)
        try {
            Files.writeString(
                temporaryOutput,
                content,
                Charsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            replaceAtomically(temporaryOutput, output)
        } finally {
            Files.deleteIfExists(temporaryOutput)
        }
    }

    /**
     * Copies one stable source snapshot into [output].
     *
     * A concurrent producer never exposes a partial generated resource: if
     * file metadata changes during the copy, the old output is retained and
     * the build fails. In continuous mode Gradle observes that input change
     * and schedules another build after its quiet period.
     */
    internal fun copyAtomically(
        source: Path,
        output: Path,
    ) {
        Files.createDirectories(output.parent)
        if (Files.exists(output) && Files.mismatch(source, output) == -1L) return

        val before = Files.readAttributes(source, BasicFileAttributes::class.java)
            .toStableStamp()
        val temporaryOutput = temporarySibling(output)
        try {
            Files.copy(source, temporaryOutput, StandardCopyOption.REPLACE_EXISTING)
            val after = Files.readAttributes(source, BasicFileAttributes::class.java)
                .toStableStamp()
            if (before != after || Files.size(temporaryOutput) != after.size) {
                throw GradleException(
                    "kwasm guest input changed while it was being embedded: $source",
                )
            }
            replaceAtomically(temporaryOutput, output)
        } finally {
            Files.deleteIfExists(temporaryOutput)
        }
    }

    private fun temporarySibling(output: Path): Path =
        Files.createTempFile(
            output.parent,
            ".${output.fileName}.",
            ".kwasm-tmp",
        )

    private fun replaceAtomically(
        temporaryOutput: Path,
        output: Path,
    ) {
        try {
            Files.move(
                temporaryOutput,
                output,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporaryOutput,
                output,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun BasicFileAttributes.toStableStamp(): StableStamp =
        StableStamp(
            size = size(),
            lastModifiedMillis = lastModifiedTime().toMillis(),
            fileKey = fileKey()?.toString(),
        )

    private data class StableStamp(
        val size: Long,
        val lastModifiedMillis: Long,
        val fileKey: String?,
    )

    private const val DIGEST_BUFFER_SIZE: Int = 64 * 1024
}
