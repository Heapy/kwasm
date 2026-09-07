package io.heapy.kwasm

/**
 * Compile-time selector for the backend-specific linear interpreter loop.
 *
 * The two loop forms are kept because each backend prefers a different one, not
 * because they drifted apart. Measured 2026-09-07 on an idle 16-core Linux
 * x86_64 host with the optimizationStudy workloads, two alternating passes per
 * form: hoisting wins on Kotlin/Native by 15.5% on the SHA loop and 13.7% on
 * the JSON parser, while on the JVM it loses 6% on the JSON parser and ties
 * elsewhere. The call-heavy fib workload cannot resolve either way. Collapsing
 * to one form would therefore cost one backend or the other.
 */
internal expect val USE_HOISTED_LINEAR_HOT_LOOP: Boolean
