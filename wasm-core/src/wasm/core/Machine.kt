package io.heapy.kwasm

/** Execution-engine seam. The v1 implementation is [Interpreter]. */
@io.heapy.kwasm.ExperimentalKwasmApi
public interface Machine {
    public suspend fun invoke(
        instance: Instance,
        functionIndex: Int,
        arguments: List<Value> = emptyList(),
    ): List<Value>
}

/** Execution engine capable of continuing heap frames restored from a snapshot. */
@io.heapy.kwasm.ExperimentalKwasmApi
public interface ResumableMachine : Machine {
    public suspend fun resume(instance: Instance): List<Value>
}
