package io.heapy.kwasm.tck

/** One auditable test exclusion. Exclusions without issue URLs are rejected. */
public data class TckExclusion(
    public val feature: String,
    public val script: String,
    public val line: Int?,
    public val issueUrl: String,
) {
    public fun matches(sourceFilename: String, commandLine: Int): Boolean {
        val normalizedSource = sourceFilename.replace('\\', '/').removePrefix("./")
        val normalizedScript = script.replace('\\', '/').removePrefix("./")
        return (normalizedSource == normalizedScript || normalizedSource.endsWith("/$normalizedScript")) &&
            (line == null || line == commandLine)
    }
}

/** Combined contents of the checked-in per-feature exclusion files. */
public class TckExclusions private constructor(public val entries: List<TckExclusion>) {
    public fun find(sourceFilename: String, line: Int): TckExclusion? =
        entries.firstOrNull { it.matches(sourceFilename, line) }

    public operator fun plus(other: TckExclusions): TckExclusions =
        TckExclusions(entries + other.entries)

    public companion object {
        public val Empty: TckExclusions = TckExclusions(emptyList())

        /**
         * Format: `<relative-script>[<:line>] <https issue URL>`.
         * Blank lines and lines whose first non-space character is `#` are ignored.
         */
        public fun parse(feature: String, text: String): TckExclusions {
            val entries = text.lines().mapIndexedNotNull { index, rawLine ->
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) return@mapIndexedNotNull null
                parseLine(feature, index + 1, trimmed)
            }
            val duplicate = entries.groupBy { it.script to it.line }.entries.firstOrNull { it.value.size > 1 }
            if (duplicate != null) {
                throw IllegalArgumentException(
                    "duplicate exclusion '${duplicate.key.first}:${duplicate.key.second ?: "*"}' in $feature",
                )
            }
            return TckExclusions(entries)
        }

        private fun parseLine(feature: String, sourceLine: Int, text: String): TckExclusion {
            val fields = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (fields.size != 2) {
                throw IllegalArgumentException(
                    "$feature exclusion line $sourceLine must be '<script>[:line] <issue-url>'",
                )
            }
            val issueUrl = fields[1]
            if (!issueUrl.startsWith("https://") || !issueUrl.looksLikeIssueUrl()) {
                throw IllegalArgumentException(
                    "$feature exclusion line $sourceLine has no HTTPS issue link: '$issueUrl'",
                )
            }
            val target = fields[0]
            val separator = target.lastIndexOf(':')
            val possibleLine = if (separator >= 0) target.substring(separator + 1).toIntOrNull() else null
            val script = if (possibleLine != null) target.substring(0, separator) else target
            require(script.endsWith(".wast")) {
                "$feature exclusion line $sourceLine must name a .wast script"
            }
            if (possibleLine != null) require(possibleLine > 0) {
                "$feature exclusion line $sourceLine has a non-positive command line"
            }
            return TckExclusion(feature, script, possibleLine, issueUrl)
        }

        private fun String.looksLikeIssueUrl(): Boolean =
            contains("/issues/") || contains("/issue/") || contains("/browse/")
    }
}
