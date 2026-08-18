package io.heapy.kwasm

/** Compile-time selector for the backend-specific linear interpreter loop. */
internal expect val USE_HOISTED_LINEAR_HOT_LOOP: Boolean
