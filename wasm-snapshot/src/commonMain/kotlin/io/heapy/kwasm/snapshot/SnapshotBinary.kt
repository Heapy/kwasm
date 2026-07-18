package io.heapy.kwasm.snapshot

import io.heapy.kwasm.ArrayObject
import io.heapy.kwasm.ArrayType
import io.heapy.kwasm.FuelExhaustionPolicy
import io.heapy.kwasm.FuncType
import io.heapy.kwasm.GcObject
import io.heapy.kwasm.GuestException
import io.heapy.kwasm.Instance
import io.heapy.kwasm.RuntimeHostSnapshot
import io.heapy.kwasm.RuntimeBodyBranch
import io.heapy.kwasm.RuntimeBodyStep
import io.heapy.kwasm.RuntimeControlKind
import io.heapy.kwasm.RuntimeControlSnapshot
import io.heapy.kwasm.RuntimeFrameSnapshot
import io.heapy.kwasm.RuntimeInstanceSnapshot
import io.heapy.kwasm.RuntimeMemorySnapshot
import io.heapy.kwasm.RuntimePendingImportSnapshot
import io.heapy.kwasm.RuntimeStoreSnapshot
import io.heapy.kwasm.RuntimeTableSnapshot
import io.heapy.kwasm.SnapshotFormatException
import io.heapy.kwasm.SnapshotStateException
import io.heapy.kwasm.Store
import io.heapy.kwasm.StructObject
import io.heapy.kwasm.StructType
import io.heapy.kwasm.TagInstance
import io.heapy.kwasm.Value
import io.heapy.kwasm.WASM_RUNTIME_VERSION

internal object SnapshotBinary {
    private val magic = byteArrayOf(
        'K'.code.toByte(),
        'W'.code.toByte(),
        'A'.code.toByte(),
        'S'.code.toByte(),
        'M'.code.toByte(),
        'S'.code.toByte(),
        '\r'.code.toByte(),
        '\n'.code.toByte(),
    )

    private val FLAG_CANONICAL_NANS: ULong = 1uL
    private val FLAG_PENDING_IMPORT: ULong = 1uL shl 1
    private val FLAG_EXECUTION_STACKS: ULong = 1uL shl 2
    private val FLAG_EXTERN_REFS: ULong = 1uL shl 3
    private val FLAG_HEAP_GRAPH: ULong = 1uL shl 4
    private val FLAG_HOST_EXTERN_REFS: ULong = 1uL shl 5
    private val FLAG_CONVERTED_REFS: ULong = 1uL shl 6
    private val FLAG_CAUGHT_EXCEPTIONS: ULong = 1uL shl 7
    private val FLAG_HOST_STATE: ULong = 1uL shl 8
    private val KNOWN_FLAGS: ULong =
        FLAG_CANONICAL_NANS or FLAG_PENDING_IMPORT or FLAG_EXECUTION_STACKS or
            FLAG_EXTERN_REFS or FLAG_HEAP_GRAPH or FLAG_HOST_EXTERN_REFS or
            FLAG_CONVERTED_REFS or FLAG_CAUGHT_EXCEPTIONS or FLAG_HOST_STATE

    private const val SECTION_CONFIGURATION = 1
    private const val SECTION_MEMORIES = 2
    private const val SECTION_TABLES = 3
    private const val SECTION_GLOBALS = 4
    private const val SECTION_VALUE_STACK = 5
    private const val SECTION_FRAMES = 6
    private const val SECTION_PENDING_IMPORT = 7
    private const val SECTION_SEGMENTS = 8
    private const val SECTION_HEAP_GRAPH = 9
    private const val SECTION_CAUGHT_EXCEPTIONS = 10
    private const val SECTION_HOST_STATE = 11
    private const val REQUIRED_SECTION_COUNT = 8

    fun encode(
        instance: Instance,
        store: Store,
        state: RuntimeStoreSnapshot,
        hooks: SnapshotHooks?,
        limits: SnapshotLimits,
        preCapturedHostStates: List<RuntimeHostSnapshot>? = null,
    ): ByteArray {
        validateEncodeLimits(state, limits)
        val externs = ExternEncoder(hooks, limits)
        val graph = SnapshotGraphEncoder(instance, externs, limits)
        graph.scan(state)
        externs.throwIfMissing()
        val hostStates =
            preCapturedHostStates ?: store.captureHostSnapshotState(instance, hooks)
        validateHostStates(hostStates, limits)

        var flags = FLAG_CANONICAL_NANS
        if (state.pendingImport != null) flags = flags or FLAG_PENDING_IMPORT
        if (state.frames.isNotEmpty() || state.valueStack().isNotEmpty()) {
            flags = flags or FLAG_EXECUTION_STACKS
        }
        if (externs.hasExternRefs) flags = flags or FLAG_EXTERN_REFS
        if (graph.nodeCount > 0) flags = flags or FLAG_HEAP_GRAPH
        if (externs.hasHostExternRefs) flags = flags or FLAG_HOST_EXTERN_REFS
        if (graph.hasConvertedRefs) flags = flags or FLAG_CONVERTED_REFS
        val hasCaughtExceptions = state.frames.any { frame ->
            frame.controls.any { it.caughtException != null }
        }
        if (hasCaughtExceptions) flags = flags or FLAG_CAUGHT_EXCEPTIONS
        if (hostStates.isNotEmpty()) flags = flags or FLAG_HOST_STATE

        val writer = SnapshotWriter(limits.maxSnapshotBytes, limits.maxSectionBytes)
        writer.bytes(magic)
        writer.u16(KwasmSnapshot.FORMAT_VERSION)
        writer.u64(flags)
        writer.utf8U16(WASM_RUNTIME_VERSION, limits.maxRuntimeVersionBytes, "runtime version")
        writer.bytes(sha256(instance.module.encodedBytes()))
        writer.u16(
            REQUIRED_SECTION_COUNT +
                (if (graph.nodeCount > 0) 1 else 0) +
                (if (hasCaughtExceptions) 1 else 0) +
                (if (hostStates.isNotEmpty()) 1 else 0),
        )

        writer.section(SECTION_CONFIGURATION) {
            u32(store.config.limits.maxFrames)
            u32(store.config.limits.maxValueStackSlots)
            u32(store.config.checkpointInterval)
            u8(if (store.config.fuelEnabled) 1 else 0)
            u8(
                when (store.config.fuelExhaustionPolicy) {
                    FuelExhaustionPolicy.Trap -> 0
                    FuelExhaustionPolicy.Suspend -> 1
                },
            )
            u16(0)
            i64(state.fuel)
            u32(state.instructionsUntilCheckpoint)
        }
        writer.section(SECTION_MEMORIES) {
            u32(state.instance.memories.size)
            state.instance.memories.forEach { memory ->
                val bytes = memory.bytes()
                u64(bytes.size.toULong())
                bytes(bytes)
            }
        }
        writer.section(SECTION_TABLES) {
            u32(state.instance.tables.size)
            state.instance.tables.forEachIndexed { tableIndex, table ->
                val values = table.values()
                u32(values.size)
                values.forEachIndexed { index, value ->
                    value(value, "table $tableIndex element $index", externs, graph)
                }
            }
        }
        writer.section(SECTION_GLOBALS) {
            val values = state.instance.globals()
            u32(values.size)
            values.forEachIndexed { index, value ->
                value(value, "global $index", externs, graph)
            }
        }
        writer.section(SECTION_VALUE_STACK) {
            val values = state.valueStack()
            u32(values.size)
            values.forEachIndexed { index, value ->
                value(value, "value stack slot $index", externs, graph)
            }
        }
        writer.section(SECTION_FRAMES) {
            u32(state.frames.size)
            state.frames.forEachIndexed { frameIndex, frame ->
                u32(frame.functionIndex)
                u32(frame.stackBase)
                val locals = frame.locals()
                u32(locals.size)
                locals.forEachIndexed { localIndex, value ->
                    value(value, "frame $frameIndex local $localIndex", externs, graph)
                }
                u32(frame.controls.size)
                frame.controls.forEach { control ->
                    u8(control.kind.ordinal)
                    u8(0)
                    u16(0)
                    u32(control.pc)
                    u32(control.stackBase)
                    u32(control.parameterCount)
                    u32(control.resultCount)
                    u32(control.labelArity)
                    u32(control.bodyPath.size)
                    control.bodyPath.forEach { step ->
                        u32(step.instructionIndex)
                        u8(step.branch.ordinal)
                        u8(0)
                        u16(0)
                        i32(step.branchIndex)
                    }
                }
            }
        }
        writer.section(SECTION_PENDING_IMPORT) {
            val pending = state.pendingImport
            u8(if (pending == null) 0 else 1)
            if (pending != null) {
                u32(pending.functionIndex)
                val arguments = pending.arguments()
                u32(arguments.size)
                arguments.forEachIndexed { index, value ->
                    value(value, "pending import argument $index", externs, graph)
                }
            }
        }
        writer.section(SECTION_SEGMENTS) {
            u32(state.instance.elementSegmentsDropped.size)
            state.instance.elementSegmentsDropped.forEach { dropped ->
                u8(if (dropped) 1 else 0)
            }
            val dataSegments = state.instance.dataSegments()
            u32(dataSegments.size)
            dataSegments.forEach { bytes ->
                u64(bytes.size.toULong())
                bytes(bytes)
            }
        }
        if (graph.nodeCount > 0) {
            writer.section(SECTION_HEAP_GRAPH) {
                graph.writeTo(this)
            }
        }
        if (hasCaughtExceptions) {
            writer.section(SECTION_CAUGHT_EXCEPTIONS) {
                u32(state.frames.size)
                state.frames.forEachIndexed { frameIndex, frame ->
                    u32(frame.controls.size)
                    frame.controls.forEachIndexed { controlIndex, control ->
                        value(
                            Value.Ref.Exn(control.caughtException),
                            "frame $frameIndex control $controlIndex caught exception",
                            externs,
                            graph,
                        )
                    }
                }
            }
        }
        if (hostStates.isNotEmpty()) {
            writer.section(SECTION_HOST_STATE) {
                u32(hostStates.size)
                hostStates.forEach { snapshot ->
                    utf8U16(
                        snapshot.participantId,
                        limits.maxHostParticipantIdBytes,
                        "host participant id",
                    )
                    u64(snapshot.size.toULong())
                    bytes(snapshot.payload())
                }
            }
        }
        return writer.toByteArray()
    }

    fun inspect(bytes: ByteArray, limits: SnapshotLimits): SnapshotHeader {
        val parsed = parseHeader(bytes, limits)
        repeat(parsed.header.sectionCount) {
            parsed.reader.u16("section id")
            parsed.reader.u16("section flags")
            val size = parsed.reader.length("section payload", limits.maxSectionBytes)
            parsed.reader.skip(size, "section payload")
        }
        parsed.reader.requireEof("snapshot")
        return parsed.header
    }

    fun decode(
        bytes: ByteArray,
        hooks: SnapshotHooks?,
        limits: SnapshotLimits,
    ): DecodedSnapshot {
        val parsed = parseHeader(bytes, limits)
        val header = parsed.header
        if (header.featureFlags and KNOWN_FLAGS.inv() != 0uL) {
            throw SnapshotFormatException(
                "unsupported feature flags 0x${(header.featureFlags and KNOWN_FLAGS.inv()).toString(16)}",
            )
        }
        if (header.featureFlags and FLAG_CANONICAL_NANS == 0uL) {
            throw SnapshotFormatException("snapshot does not declare canonical NaN mode")
        }

        var configurationPayload: SnapshotReader? = null
        var memoriesPayload: SnapshotReader? = null
        var tablesPayload: SnapshotReader? = null
        var globalsPayload: SnapshotReader? = null
        var stackPayload: SnapshotReader? = null
        var framesPayload: SnapshotReader? = null
        var pendingPayload: SnapshotReader? = null
        var segmentsPayload: SnapshotReader? = null
        var graphPayload: SnapshotReader? = null
        var caughtPayload: SnapshotReader? = null
        var hostStatePayload: SnapshotReader? = null
        val seen = mutableSetOf<Int>()

        repeat(header.sectionCount) {
            val id = parsed.reader.u16("section id")
            val sectionFlags = parsed.reader.u16("section flags")
            val size = parsed.reader.length("section $id payload", limits.maxSectionBytes)
            val payload = parsed.reader.slice(size, "section $id payload")
            if (id in SECTION_CONFIGURATION..SECTION_HOST_STATE) {
                if (!seen.add(id)) {
                    throw SnapshotFormatException("duplicate section $id")
                }
                if (sectionFlags != 0) {
                    throw SnapshotFormatException("section $id has unsupported flags $sectionFlags")
                }
            }
            when (id) {
                SECTION_CONFIGURATION -> configurationPayload = payload
                SECTION_MEMORIES -> memoriesPayload = payload
                SECTION_TABLES -> tablesPayload = payload
                SECTION_GLOBALS -> globalsPayload = payload
                SECTION_VALUE_STACK -> stackPayload = payload
                SECTION_FRAMES -> framesPayload = payload
                SECTION_PENDING_IMPORT -> pendingPayload = payload
                SECTION_SEGMENTS -> segmentsPayload = payload
                SECTION_HEAP_GRAPH -> graphPayload = payload
                SECTION_CAUGHT_EXCEPTIONS -> caughtPayload = payload
                SECTION_HOST_STATE -> hostStatePayload = payload
                else -> Unit // Unknown self-describing sections are safely skipped.
            }
        }
        parsed.reader.requireEof("snapshot")
        val seenRequired = seen.count { it in SECTION_CONFIGURATION..SECTION_SEGMENTS }
        if (seenRequired != REQUIRED_SECTION_COUNT) {
            val missing = (SECTION_CONFIGURATION..SECTION_SEGMENTS).filterNot(seen::contains)
            throw SnapshotFormatException("missing required sections ${missing.joinToString()}")
        }
        val declaresGraph = header.featureFlags and FLAG_HEAP_GRAPH != 0uL
        if (declaresGraph != (graphPayload != null)) {
            throw SnapshotFormatException(
                if (declaresGraph) {
                    "snapshot declares a heap graph but has no heap graph section"
                } else {
                    "snapshot has a heap graph section without the heap graph feature flag"
                },
            )
        }
        val declaresCaughtExceptions =
            header.featureFlags and FLAG_CAUGHT_EXCEPTIONS != 0uL
        if (declaresCaughtExceptions != (caughtPayload != null)) {
            throw SnapshotFormatException(
                if (declaresCaughtExceptions) {
                    "snapshot declares caught exceptions but has no caught-exceptions section"
                } else {
                    "snapshot has a caught-exceptions section without its feature flag"
                },
            )
        }
        val declaresHostState = header.featureFlags and FLAG_HOST_STATE != 0uL
        if (declaresHostState != (hostStatePayload != null)) {
            throw SnapshotFormatException(
                if (declaresHostState) {
                    "snapshot declares host state but has no host-state section"
                } else {
                    "snapshot has a host-state section without its feature flag"
                },
            )
        }

        val configReader = configurationPayload!!
        val configuration = SnapshotConfiguration(
            maxFrames = configReader.int("maximum frames"),
            maxValueStackSlots = configReader.int("maximum value stack slots"),
            checkpointInterval = configReader.int("checkpoint interval"),
            fuelEnabled = configReader.boolean("fuel enabled"),
            fuelExhaustionPolicy = when (configReader.u8("fuel policy")) {
                0 -> FuelExhaustionPolicy.Trap
                1 -> FuelExhaustionPolicy.Suspend
                else -> throw SnapshotFormatException("unknown fuel exhaustion policy")
            },
        )
        val reserved = configReader.u16("configuration reserved field")
        if (reserved != 0) throw SnapshotFormatException("configuration reserved field is non-zero")
        val fuel = configReader.i64("fuel")
        if (fuel < 0) throw SnapshotFormatException("fuel is negative")
        val checkpointCountdown = configReader.int("checkpoint countdown")
        configReader.requireEof("configuration section")

        val externs = ExternDecoder(hooks)
        val graph = graphPayload?.let { decodeHeapGraph(it, externs, limits) }
            ?: DecodedGraph.empty()
        val memories = decodeMemories(memoriesPayload!!, limits)
        val tables = decodeTables(tablesPayload!!, externs, graph, limits)
        val globals = decodeValues(
            globalsPayload!!,
            "global",
            limits.maxGlobals,
            externs,
            graph,
            limits,
        )
        val valueStack = decodeValues(
            stackPayload!!,
            "value stack slot",
            limits.maxValues,
            externs,
            graph,
            limits,
        )
        val baseFrames = decodeFrames(framesPayload!!, externs, graph, limits)
        val frames = caughtPayload?.let {
            decodeCaughtExceptions(it, baseFrames, externs, graph, limits)
        } ?: baseFrames
        val pending = decodePendingImport(pendingPayload!!, externs, graph, limits)
        val segments = decodeSegments(segmentsPayload!!, limits)
        val hostStates = hostStatePayload?.let { decodeHostStates(it, limits) }
            ?: emptyList()
        externs.throwIfMissing()

        val instance = RuntimeInstanceSnapshot(
            memories = memories,
            tables = tables,
            globals = globals,
            elementSegmentsDropped = segments.first,
            dataSegments = segments.second,
        )
        val state = RuntimeStoreSnapshot(
            instance = instance,
            valueStack = valueStack,
            frames = frames,
            pendingImport = pending,
            fuel = fuel,
            instructionsUntilCheckpoint = checkpointCountdown,
        )
        return DecodedSnapshot(header, configuration, state, hostStates)
    }

    private fun parseHeader(bytes: ByteArray, limits: SnapshotLimits): ParsedHeader {
        if (bytes.size > limits.maxSnapshotBytes) {
            throw SnapshotFormatException(
                "snapshot has ${bytes.size} bytes; limit is ${limits.maxSnapshotBytes}",
            )
        }
        val reader = SnapshotReader(bytes)
        val actualMagic = reader.bytes(magic.size, "magic")
        if (!actualMagic.contentEquals(magic)) {
            throw SnapshotFormatException("bad snapshot magic")
        }
        val formatVersion = reader.u16("format version")
        if (formatVersion != KwasmSnapshot.FORMAT_VERSION) {
            throw SnapshotFormatException(
                "unsupported snapshot format version $formatVersion",
            )
        }
        val flags = reader.u64("feature flags")
        val runtimeVersion = reader.utf8U16(
            limits.maxRuntimeVersionBytes,
            "runtime version",
        )
        val moduleHash = reader.bytes(32, "module SHA-256")
        val sectionCount = reader.u16("section count")
        if (sectionCount > limits.maxSections) {
            throw SnapshotFormatException(
                "section count $sectionCount exceeds limit ${limits.maxSections}",
            )
        }
        return ParsedHeader(
            SnapshotHeader(formatVersion, runtimeVersion, flags, moduleHash, sectionCount),
            reader,
        )
    }

    private fun decodeMemories(
        reader: SnapshotReader,
        limits: SnapshotLimits,
    ): List<RuntimeMemorySnapshot> {
        val count = reader.count("memory", limits.maxMemories, 8)
        var total = 0L
        val memories = ArrayList<RuntimeMemorySnapshot>(count)
        repeat(count) { index ->
            val size = reader.length("memory $index bytes", limits.maxSectionBytes)
            total = checkedAdd(total, size.toLong(), "total memory byte count")
            if (total > limits.maxTotalMemoryBytes) {
                throw SnapshotFormatException(
                    "total memory bytes $total exceed limit ${limits.maxTotalMemoryBytes}",
                )
            }
            memories += RuntimeMemorySnapshot(reader.bytes(size, "memory $index bytes"))
        }
        reader.requireEof("memories section")
        return memories
    }

    private fun decodeTables(
        reader: SnapshotReader,
        externs: ExternDecoder,
        graph: DecodedGraph,
        limits: SnapshotLimits,
    ): List<RuntimeTableSnapshot> {
        val count = reader.count("table", limits.maxTables, 4)
        var totalElements = 0L
        val tables = ArrayList<RuntimeTableSnapshot>(count)
        repeat(count) { tableIndex ->
            val elementCount = reader.count(
                "table $tableIndex element",
                limits.maxTableElements,
                1,
            )
            totalElements = checkedAdd(totalElements, elementCount.toLong(), "table element count")
            if (totalElements > limits.maxTableElements) {
                throw SnapshotFormatException(
                    "total table elements $totalElements exceed limit ${limits.maxTableElements}",
                )
            }
            val values = ArrayList<Value.Ref>(elementCount)
            repeat(elementCount) { elementIndex ->
                val value = reader.value(
                    "table $tableIndex element $elementIndex",
                    externs,
                    graph,
                    limits,
                )
                values += value as? Value.Ref
                    ?: throw SnapshotFormatException(
                        "table $tableIndex element $elementIndex is not a reference",
                    )
            }
            tables += RuntimeTableSnapshot(values)
        }
        reader.requireEof("tables section")
        return tables
    }

    private fun decodeValues(
        reader: SnapshotReader,
        label: String,
        maximum: Int,
        externs: ExternDecoder,
        graph: DecodedGraph,
        limits: SnapshotLimits = SnapshotLimits(),
    ): List<Value> {
        val count = reader.count(label, maximum, 1)
        val values = ArrayList<Value>(count)
        repeat(count) { index ->
            values += reader.value("$label $index", externs, graph, limits)
        }
        reader.requireEof("$label section")
        return values
    }

    private fun decodeFrames(
        reader: SnapshotReader,
        externs: ExternDecoder,
        graph: DecodedGraph,
        limits: SnapshotLimits,
    ): List<RuntimeFrameSnapshot> {
        val count = reader.count("frame", limits.maxFrames, 16)
        val frames = ArrayList<RuntimeFrameSnapshot>(count)
        repeat(count) { frameIndex ->
            val functionIndex = reader.int("frame $frameIndex function index")
            val stackBase = reader.int("frame $frameIndex stack base")
            val localCount = reader.count(
                "frame $frameIndex local",
                limits.maxLocalsPerFrame,
                1,
            )
            val locals = ArrayList<Value>(localCount)
            repeat(localCount) { localIndex ->
                locals += reader.value(
                    "frame $frameIndex local $localIndex",
                    externs,
                    graph,
                    limits,
                )
            }
            val controlCount = reader.count(
                "frame $frameIndex control",
                limits.maxControlsPerFrame,
                28,
            )
            val controls = ArrayList<RuntimeControlSnapshot>(controlCount)
            repeat(controlCount) { controlIndex ->
                val kindCode = reader.u8("frame $frameIndex control $controlIndex kind")
                val kind = RuntimeControlKind.entries.getOrNull(kindCode)
                    ?: throw SnapshotFormatException(
                        "frame $frameIndex control $controlIndex has unknown kind $kindCode",
                    )
                if (reader.u8("control reserved byte") != 0 ||
                    reader.u16("control reserved field") != 0
                ) {
                    throw SnapshotFormatException(
                        "frame $frameIndex control $controlIndex has non-zero reserved fields",
                    )
                }
                val pc = reader.int("frame $frameIndex control $controlIndex pc")
                val controlStackBase = reader.int(
                    "frame $frameIndex control $controlIndex stack base",
                )
                val parameterCount = reader.int(
                    "frame $frameIndex control $controlIndex parameter count",
                )
                val resultCount = reader.int(
                    "frame $frameIndex control $controlIndex result count",
                )
                val labelArity = reader.int(
                    "frame $frameIndex control $controlIndex label arity",
                )
                val pathCount = reader.count(
                    "frame $frameIndex control $controlIndex body path",
                    limits.maxBodyPathDepth,
                    12,
                )
                val path = ArrayList<RuntimeBodyStep>(pathCount)
                repeat(pathCount) { pathIndex ->
                    val instructionIndex = reader.int(
                        "frame $frameIndex control $controlIndex body path $pathIndex instruction",
                    )
                    val branchCode = reader.u8("control body branch")
                    val branch = RuntimeBodyBranch.entries.getOrNull(branchCode)
                        ?: throw SnapshotFormatException(
                            "frame $frameIndex control $controlIndex body path $pathIndex " +
                                "has unknown branch $branchCode",
                        )
                    if (reader.u8("body path reserved byte") != 0 ||
                        reader.u16("body path reserved field") != 0
                    ) {
                        throw SnapshotFormatException("control body path has non-zero reserved fields")
                    }
                    val branchIndex = reader.i32("control body branch index")
                    if (
                        branch == RuntimeBodyBranch.Catch && branchIndex < 0 ||
                        branch != RuntimeBodyBranch.Catch && branchIndex != -1
                    ) {
                        throw SnapshotFormatException(
                            "control body path has invalid branch index $branchIndex for $branch",
                        )
                    }
                    path += RuntimeBodyStep(instructionIndex, branch, branchIndex)
                }
                controls += RuntimeControlSnapshot(
                    kind,
                    path,
                    pc,
                    controlStackBase,
                    parameterCount,
                    resultCount,
                    labelArity,
                )
            }
            frames += RuntimeFrameSnapshot(functionIndex, locals, stackBase, controls)
        }
        reader.requireEof("frames section")
        return frames
    }

    private fun decodePendingImport(
        reader: SnapshotReader,
        externs: ExternDecoder,
        graph: DecodedGraph,
        limits: SnapshotLimits,
    ): RuntimePendingImportSnapshot? {
        val present = reader.boolean("pending import presence")
        val result = if (present) {
            val functionIndex = reader.int("pending import function index")
            val count = reader.count("pending import argument", limits.maxValues, 1)
            val arguments = ArrayList<Value>(count)
            repeat(count) { index ->
                arguments += reader.value(
                    "pending import argument $index",
                    externs,
                    graph,
                    limits,
                )
            }
            RuntimePendingImportSnapshot(functionIndex, arguments)
        } else {
            null
        }
        reader.requireEof("pending import section")
        return result
    }

    private fun decodeCaughtExceptions(
        reader: SnapshotReader,
        frames: List<RuntimeFrameSnapshot>,
        externs: ExternDecoder,
        graph: DecodedGraph,
        limits: SnapshotLimits,
    ): List<RuntimeFrameSnapshot> {
        val frameCount = reader.count("caught-exception frame", limits.maxFrames, 4)
        if (frameCount != frames.size) {
            throw SnapshotFormatException(
                "caught-exception frame count $frameCount does not match frame count ${frames.size}",
            )
        }
        val result = ArrayList<RuntimeFrameSnapshot>(frameCount)
        frames.forEachIndexed { frameIndex, frame ->
            val controlCount = reader.count(
                "frame $frameIndex caught-exception control",
                limits.maxControlsPerFrame,
                2,
            )
            if (controlCount != frame.controls.size) {
                throw SnapshotFormatException(
                    "frame $frameIndex caught-exception control count $controlCount " +
                        "does not match control count ${frame.controls.size}",
                )
            }
            val controls = ArrayList<RuntimeControlSnapshot>(controlCount)
            frame.controls.forEachIndexed { controlIndex, control ->
                val reference = reader.value(
                    "frame $frameIndex control $controlIndex caught exception",
                    externs,
                    graph,
                    limits,
                ) as? Value.Ref.Exn ?: throw SnapshotFormatException(
                    "frame $frameIndex control $controlIndex caught exception " +
                        "is not an exception reference",
                )
                controls += RuntimeControlSnapshot(
                    kind = control.kind,
                    bodyPath = control.bodyPath,
                    pc = control.pc,
                    stackBase = control.stackBase,
                    parameterCount = control.parameterCount,
                    resultCount = control.resultCount,
                    labelArity = control.labelArity,
                    caughtException = reference.value,
                )
            }
            result += RuntimeFrameSnapshot(
                functionIndex = frame.functionIndex,
                locals = frame.locals(),
                stackBase = frame.stackBase,
                controls = controls,
            )
        }
        reader.requireEof("caught-exceptions section")
        return result
    }

    private fun decodeHeapGraph(
        reader: SnapshotReader,
        externs: ExternDecoder,
        limits: SnapshotLimits,
    ): DecodedGraph {
        val count = reader.count("heap node", limits.maxHeapObjects, 12)
        val descriptors = ArrayList<DecodedGraphNode>(count)
        val valueCounts = IntArray(count)
        var totalValues = 0L
        repeat(count) { nodeIndex ->
            val kind = reader.u8("heap node $nodeIndex kind")
            if (
                reader.u8("heap node $nodeIndex reserved byte") != 0 ||
                reader.u16("heap node $nodeIndex reserved field") != 0
            ) {
                throw SnapshotFormatException(
                    "heap node $nodeIndex has non-zero reserved fields",
                )
            }
            val typeOrTagIndex = reader.int("heap node $nodeIndex type/tag index")
            val valueCount = reader.int("heap node $nodeIndex value count")
            valueCounts[nodeIndex] = valueCount
            if (valueCount > limits.maxHeapValues) {
                throw SnapshotFormatException(
                    "heap node $nodeIndex value count $valueCount exceeds limit " +
                        limits.maxHeapValues,
                )
            }
            totalValues = checkedAdd(
                totalValues,
                valueCount.toLong(),
                "heap graph value count",
            )
            if (totalValues > limits.maxHeapValues) {
                throw SnapshotFormatException(
                    "heap graph value count $totalValues exceeds limit ${limits.maxHeapValues}",
                )
            }
            val values = mutableListOf<Value>()
            val node = when (kind) {
                0 -> DecodedGraphNode.Gc(
                    StructObject(null, typeOrTagIndex, values),
                    values,
                )
                1 -> DecodedGraphNode.Gc(
                    ArrayObject(null, typeOrTagIndex, values),
                    values,
                )
                2 -> DecodedGraphNode.Exception(
                    GuestException(
                        TagInstance(FuncType(emptyList(), emptyList())),
                        values,
                        typeOrTagIndex,
                    ),
                    values,
                )
                else -> throw SnapshotFormatException(
                    "heap node $nodeIndex has unknown kind $kind",
                )
            }
            descriptors += node
        }

        val graph = DecodedGraph(descriptors)
        descriptors.forEachIndexed { nodeIndex, node ->
            repeat(valueCounts[nodeIndex]) { valueIndex ->
                node.values += reader.value(
                    "heap node $nodeIndex value $valueIndex",
                    externs,
                    graph,
                    limits,
                )
            }
        }
        reader.requireEof("heap graph section")
        return graph
    }

    private fun decodeSegments(
        reader: SnapshotReader,
        limits: SnapshotLimits,
    ): Pair<List<Boolean>, List<ByteArray>> {
        val elementCount = reader.count("element segment", limits.maxSegments, 1)
        val dropped = ArrayList<Boolean>(elementCount)
        repeat(elementCount) { index ->
            dropped += reader.boolean("element segment $index dropped")
        }
        val dataCount = reader.count("data segment", limits.maxSegments, 8)
        val data = ArrayList<ByteArray>(dataCount)
        repeat(dataCount) { index ->
            val size = reader.length("data segment $index bytes", limits.maxBlobBytes)
            data += reader.bytes(size, "data segment $index bytes")
        }
        reader.requireEof("segments section")
        return dropped to data
    }

    private fun decodeHostStates(
        reader: SnapshotReader,
        limits: SnapshotLimits,
    ): List<RuntimeHostSnapshot> {
        val count = reader.count(
            "host participant",
            limits.maxHostParticipants,
            10,
        )
        val seen = mutableSetOf<String>()
        val snapshots = ArrayList<RuntimeHostSnapshot>(count)
        repeat(count) { index ->
            val id = reader.utf8U16(
                limits.maxHostParticipantIdBytes,
                "host participant $index id",
            )
            if (id.isBlank() || '\u0000' in id) {
                throw SnapshotFormatException(
                    "host participant $index id must be non-blank and contain no NUL",
                )
            }
            if (!seen.add(id)) {
                throw SnapshotFormatException("duplicate host participant '$id'")
            }
            val size = reader.length(
                "host participant '$id' state",
                limits.maxHostParticipantStateBytes,
            )
            snapshots += RuntimeHostSnapshot(
                id,
                reader.bytes(size, "host participant '$id' state"),
            )
        }
        reader.requireEof("host-state section")
        return snapshots
    }

    private fun checkedAdd(a: Long, b: Long, label: String): Long {
        if (b < 0 || Long.MAX_VALUE - a < b) {
            throw SnapshotFormatException("$label overflows")
        }
        return a + b
    }

    private fun validateEncodeLimits(
        state: RuntimeStoreSnapshot,
        limits: SnapshotLimits,
    ) {
        if (state.instance.memories.size > limits.maxMemories) {
            throw SnapshotStateException(
                "memory count ${state.instance.memories.size} exceeds limit ${limits.maxMemories}",
            )
        }
        var memoryBytes = 0L
        state.instance.memories.forEach { memory ->
            memoryBytes = checkedAdd(memoryBytes, memory.size.toLong(), "total memory byte count")
        }
        if (memoryBytes > limits.maxTotalMemoryBytes) {
            throw SnapshotStateException(
                "total memory bytes $memoryBytes exceed limit ${limits.maxTotalMemoryBytes}",
            )
        }
        if (state.instance.tables.size > limits.maxTables) {
            throw SnapshotStateException(
                "table count ${state.instance.tables.size} exceeds limit ${limits.maxTables}",
            )
        }
        val tableElements = state.instance.tables.sumOf { it.size.toLong() }
        if (tableElements > limits.maxTableElements) {
            throw SnapshotStateException(
                "table element count $tableElements exceeds limit ${limits.maxTableElements}",
            )
        }
        if (state.instance.globals().size > limits.maxGlobals) {
            throw SnapshotStateException(
                "global count exceeds limit ${limits.maxGlobals}",
            )
        }
        if (state.valueStack().size > limits.maxValues) {
            throw SnapshotStateException(
                "value stack size exceeds limit ${limits.maxValues}",
            )
        }
        if (state.frames.size > limits.maxFrames) {
            throw SnapshotStateException("frame count exceeds limit ${limits.maxFrames}")
        }
        state.frames.forEachIndexed { index, frame ->
            if (frame.locals().size > limits.maxLocalsPerFrame) {
                throw SnapshotStateException(
                    "frame $index local count exceeds limit ${limits.maxLocalsPerFrame}",
                )
            }
            if (frame.controls.size > limits.maxControlsPerFrame) {
                throw SnapshotStateException(
                    "frame $index control count exceeds limit ${limits.maxControlsPerFrame}",
                )
            }
            frame.controls.forEachIndexed { controlIndex, control ->
                if (control.bodyPath.size > limits.maxBodyPathDepth) {
                    throw SnapshotStateException(
                        "frame $index control $controlIndex body path exceeds limit " +
                            limits.maxBodyPathDepth,
                    )
                }
            }
        }
        state.pendingImport?.let {
            if (it.arguments().size > limits.maxValues) {
                throw SnapshotStateException(
                    "pending import argument count exceeds limit ${limits.maxValues}",
                )
            }
        }
        if (
            state.instance.elementSegmentsDropped.size > limits.maxSegments ||
            state.instance.dataSegments().size > limits.maxSegments
        ) {
            throw SnapshotStateException("segment count exceeds limit ${limits.maxSegments}")
        }
        state.instance.dataSegments().forEachIndexed { index, bytes ->
            if (bytes.size > limits.maxBlobBytes) {
                throw SnapshotStateException(
                    "data segment $index has ${bytes.size} bytes; limit is ${limits.maxBlobBytes}",
                )
            }
        }
    }

    private fun validateHostStates(
        snapshots: List<RuntimeHostSnapshot>,
        limits: SnapshotLimits,
    ) {
        if (snapshots.size > limits.maxHostParticipants) {
            throw SnapshotStateException(
                "host participant count ${snapshots.size} exceeds limit " +
                    limits.maxHostParticipants,
            )
        }
        val seen = mutableSetOf<String>()
        snapshots.forEach { snapshot ->
            val idBytes = snapshot.participantId.encodeToByteArray()
            if (
                snapshot.participantId.isBlank() ||
                '\u0000' in snapshot.participantId ||
                idBytes.size > limits.maxHostParticipantIdBytes ||
                idBytes.size > 0xFFFF
            ) {
                throw SnapshotStateException(
                    "host participant id '${snapshot.participantId}' is invalid or exceeds " +
                        "${minOf(limits.maxHostParticipantIdBytes, 0xFFFF)} UTF-8 bytes",
                )
            }
            if (!seen.add(snapshot.participantId)) {
                throw SnapshotStateException(
                    "duplicate host participant '${snapshot.participantId}'",
                )
            }
            if (snapshot.size > limits.maxHostParticipantStateBytes) {
                throw SnapshotStateException(
                    "host participant '${snapshot.participantId}' state has ${snapshot.size} " +
                        "bytes; limit is ${limits.maxHostParticipantStateBytes}",
                )
            }
        }
    }

    private data class ParsedHeader(
        val header: SnapshotHeader,
        val reader: SnapshotReader,
    )
}

private class SnapshotWriter(
    private val maximumSize: Int,
    private val maximumSectionSize: Int,
) {
    private var buffer = ByteArray(minOf(256, maximumSize))
    private var size = 0

    fun u8(value: Int) {
        require(value in 0..0xFF)
        ensure(1)
        buffer[size++] = value.toByte()
    }

    fun u16(value: Int) {
        require(value in 0..0xFFFF)
        ensure(2)
        repeat(2) { shift -> buffer[size++] = (value ushr (shift * 8)).toByte() }
    }

    fun u32(value: Int) {
        if (value < 0) throw SnapshotStateException("cannot encode negative unsigned value $value")
        ensure(4)
        repeat(4) { shift -> buffer[size++] = (value ushr (shift * 8)).toByte() }
    }

    fun i32(value: Int) {
        ensure(4)
        repeat(4) { shift -> buffer[size++] = (value ushr (shift * 8)).toByte() }
    }

    fun i64(value: Long) = u64(value.toULong())

    fun u64(value: ULong) {
        ensure(8)
        repeat(8) { shift -> buffer[size++] = (value shr (shift * 8)).toByte() }
    }

    fun bytes(value: ByteArray) {
        ensure(value.size)
        value.copyInto(buffer, size)
        size += value.size
    }

    fun utf8U16(value: String, maximumBytes: Int, label: String) {
        val encoded = value.encodeToByteArray()
        if (encoded.size > maximumBytes || encoded.size > 0xFFFF) {
            throw SnapshotStateException(
                "$label has ${encoded.size} UTF-8 bytes; limit is ${minOf(maximumBytes, 0xFFFF)}",
            )
        }
        u16(encoded.size)
        bytes(encoded)
    }

    fun section(id: Int, body: SnapshotWriter.() -> Unit) {
        u16(id)
        u16(0)
        val lengthPosition = size
        u64(0uL)
        val payloadStart = size
        body()
        val payloadSize = size - payloadStart
        if (payloadSize > maximumSectionSize) {
            throw SnapshotStateException(
                "section $id has $payloadSize bytes; limit is $maximumSectionSize",
            )
        }
        patchU64(lengthPosition, payloadSize.toULong())
    }

    fun value(
        value: Value,
        path: String,
        externs: ExternEncoder,
        graph: SnapshotGraphEncoder,
        nesting: Int = 0,
    ) {
        graph.checkReferenceNesting(nesting, path)
        when (value) {
            is Value.I32 -> {
                u8(0)
                i32(value.v)
            }
            is Value.I64 -> {
                u8(1)
                i64(value.v)
            }
            is Value.F32 -> {
                u8(2)
                val bits = if (value.v.isNaN()) 0x7FC0_0000 else value.v.toRawBits()
                i32(bits)
            }
            is Value.F64 -> {
                u8(3)
                val bits = if (value.v.isNaN()) 0x7FF8_0000_0000_0000L else value.v.toRawBits()
                i64(bits)
            }
            is Value.V128 -> {
                if (value.bytes.size != 16) {
                    throw SnapshotStateException(
                        "$path has a v128 payload of ${value.bytes.size} bytes; expected 16",
                    )
                }
                u8(4)
                bytes(value.bytes)
            }
            is Value.Ref.Func -> {
                u8(5)
                i32(value.index)
            }
            is Value.Ref.Extern -> {
                u8(6)
                if (value.handle < 0) {
                    u8(0)
                } else {
                    u8(1)
                    val key = externs.key(value.handle)
                    u32(key.size)
                    bytes(key)
                }
            }
            is Value.Ref.I31 -> {
                u8(7)
                i32(value.value)
            }
            is Value.Ref.Host -> {
                u8(8)
                val hostValue = value.value
                if (hostValue == null) {
                    u8(0)
                } else {
                    u8(1)
                    val key = externs.hostKey(hostValue)
                    u32(key.size)
                    bytes(key)
                }
            }
            is Value.Ref.Gc -> {
                u8(9)
                val objectValue = value.value
                if (objectValue == null) {
                    u8(0)
                } else {
                    u8(1)
                    u32(graph.idOf(objectValue, path))
                }
            }
            is Value.Ref.Exn -> {
                u8(10)
                val exception = value.value
                if (exception == null) {
                    u8(0)
                } else {
                    u8(1)
                    u32(graph.idOf(exception, path))
                }
            }
            is Value.Ref.AnyExtern -> {
                u8(11)
                value(
                    value.external,
                    "$path converted externref",
                    externs,
                    graph,
                    nesting + 1,
                )
            }
        }
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun patchU64(position: Int, value: ULong) {
        repeat(8) { shift ->
            buffer[position + shift] = (value shr (shift * 8)).toByte()
        }
    }

    private fun ensure(additional: Int) {
        if (additional < 0 || size > maximumSize - additional) {
            throw SnapshotStateException(
                "encoded snapshot exceeds maximum size $maximumSize",
            )
        }
        val required = size + additional
        if (required <= buffer.size) return
        var capacity = maxOf(buffer.size, 1)
        while (capacity < required) {
            val doubled = capacity.toLong() * 2
            capacity = minOf(maximumSize.toLong(), maxOf(required.toLong(), doubled)).toInt()
            if (capacity < required) {
                throw SnapshotStateException(
                    "encoded snapshot exceeds maximum size $maximumSize",
                )
            }
        }
        buffer = buffer.copyOf(capacity)
    }
}

private class SnapshotReader(
    private val bytes: ByteArray,
    private val start: Int = 0,
    private val end: Int = bytes.size,
) {
    private var position: Int = start
    private val remaining: Int get() = end - position

    fun u8(label: String): Int {
        requireBytes(1, label)
        return bytes[position++].toInt() and 0xFF
    }

    fun boolean(label: String): Boolean = when (val value = u8(label)) {
        0 -> false
        1 -> true
        else -> throw SnapshotFormatException("$label has invalid boolean value $value at byte ${position - 1}")
    }

    fun u16(label: String): Int {
        requireBytes(2, label)
        var value = 0
        repeat(2) { shift ->
            value = value or ((bytes[position++].toInt() and 0xFF) shl (shift * 8))
        }
        return value
    }

    fun u32(label: String): UInt {
        requireBytes(4, label)
        var value = 0u
        repeat(4) { shift ->
            value = value or ((bytes[position++].toUInt() and 0xFFu) shl (shift * 8))
        }
        return value
    }

    fun int(label: String): Int {
        val value = u32(label)
        if (value > Int.MAX_VALUE.toUInt()) {
            throw SnapshotFormatException("$label $value exceeds Int.MAX_VALUE")
        }
        return value.toInt()
    }

    fun i32(label: String): Int = u32(label).toInt()

    fun u64(label: String): ULong {
        requireBytes(8, label)
        var value = 0uL
        repeat(8) { shift ->
            value = value or ((bytes[position++].toULong() and 0xFFuL) shl (shift * 8))
        }
        return value
    }

    fun i64(label: String): Long = u64(label).toLong()

    fun length(label: String, maximum: Int): Int {
        val value = u64(label)
        if (value > maximum.toULong()) {
            throw SnapshotFormatException("$label $value exceeds limit $maximum")
        }
        if (value > remaining.toULong()) {
            throw SnapshotFormatException(
                "$label $value exceeds remaining $remaining bytes at byte $position",
            )
        }
        return value.toInt()
    }

    fun count(label: String, maximum: Int, minimumEncodedBytes: Int): Int {
        val count = int("$label count")
        if (count > maximum) {
            throw SnapshotFormatException("$label count $count exceeds limit $maximum")
        }
        if (minimumEncodedBytes > 0 && count.toLong() * minimumEncodedBytes > remaining.toLong()) {
            throw SnapshotFormatException(
                "$label count $count cannot fit in remaining $remaining bytes",
            )
        }
        return count
    }

    fun bytes(length: Int, label: String): ByteArray {
        requireBytes(length, label)
        val result = bytes.copyOfRange(position, position + length)
        position += length
        return result
    }

    fun utf8U16(maximumBytes: Int, label: String): String {
        val length = u16("$label length")
        if (length > maximumBytes) {
            throw SnapshotFormatException("$label length $length exceeds limit $maximumBytes")
        }
        val raw = bytes(length, label)
        return try {
            raw.decodeToString(throwOnInvalidSequence = true)
        } catch (failure: IllegalArgumentException) {
            throw SnapshotFormatException("$label is not valid UTF-8", failure)
        }
    }

    fun slice(length: Int, label: String): SnapshotReader {
        requireBytes(length, label)
        val result = SnapshotReader(bytes, position, position + length)
        position += length
        return result
    }

    fun skip(length: Int, label: String) {
        requireBytes(length, label)
        position += length
    }

    fun requireEof(label: String) {
        if (remaining != 0) {
            throw SnapshotFormatException(
                "$label has $remaining trailing bytes at byte $position",
            )
        }
    }

    fun value(
        path: String,
        externs: ExternDecoder,
        graph: DecodedGraph,
        limits: SnapshotLimits,
        nesting: Int = 0,
    ): Value {
        if (nesting > limits.maxReferenceNesting) {
            throw SnapshotFormatException(
                "$path reference nesting $nesting exceeds limit ${limits.maxReferenceNesting}",
            )
        }
        return when (val tag = u8("$path value tag")) {
        0 -> Value.I32(i32("$path i32"))
        1 -> Value.I64(i64("$path i64"))
        2 -> {
            val value = Float.fromBits(i32("$path f32"))
            Value.F32(if (value.isNaN()) Float.fromBits(0x7FC0_0000) else value)
        }
        3 -> {
            val value = Double.fromBits(i64("$path f64"))
            Value.F64(
                if (value.isNaN()) Double.fromBits(0x7FF8_0000_0000_0000L) else value,
            )
        }
        4 -> Value.V128(bytes(16, "$path v128"))
        5 -> Value.Ref.Func(i32("$path funcref"))
        6 -> {
            if (!boolean("$path externref presence")) {
                Value.NULL_EXTERN
            } else {
                val keyLength = int("$path externref key length")
                if (keyLength > limits.maxExternKeyBytes) {
                    throw SnapshotFormatException(
                        "$path externref key length $keyLength exceeds limit ${limits.maxExternKeyBytes}",
                    )
                }
                val key = bytes(keyLength, "$path externref key")
                Value.Ref.Extern(externs.rehydrate(key, path))
            }
        }
        7 -> Value.Ref.I31(i32("$path i31ref"))
        8 -> {
            if (!boolean("$path host externref presence")) {
                Value.Ref.Host(null)
            } else {
                val keyLength = int("$path host externref key length")
                if (keyLength > limits.maxExternKeyBytes) {
                    throw SnapshotFormatException(
                        "$path host externref key length $keyLength exceeds limit " +
                            limits.maxExternKeyBytes,
                    )
                }
                val key = bytes(keyLength, "$path host externref key")
                Value.Ref.Host(externs.rehydrateHost(key, path))
            }
        }
        9 -> {
            if (!boolean("$path GC reference presence")) {
                Value.NULL_GC
            } else {
                Value.Ref.Gc(graph.gc(int("$path GC object id"), path))
            }
        }
        10 -> {
            if (!boolean("$path exception reference presence")) {
                Value.NULL_EXN
            } else {
                Value.Ref.Exn(graph.exception(int("$path exception id"), path))
            }
        }
        11 -> {
            val external = value(
                "$path converted externref",
                externs,
                graph,
                limits,
                nesting + 1,
            ) as? Value.Ref ?: throw SnapshotFormatException(
                "$path converted externref payload is not a reference",
            )
            Value.Ref.AnyExtern(external)
        }
        else -> throw SnapshotFormatException("$path has unknown value tag $tag")
        }
    }

    private fun requireBytes(length: Int, label: String) {
        if (length < 0 || length > remaining) {
            throw SnapshotFormatException(
                "truncated $label at byte $position: need $length bytes, have $remaining",
            )
        }
    }
}

private sealed class EncodedGraphNode {
    abstract val typeOrTagIndex: Int
    abstract val values: List<Value>
    abstract val kind: Int

    class Gc(
        val objectValue: GcObject,
        override val typeOrTagIndex: Int,
        override val values: List<Value>,
        override val kind: Int,
    ) : EncodedGraphNode()

    class Exception(
        val exception: GuestException,
        override val typeOrTagIndex: Int,
        override val values: List<Value>,
    ) : EncodedGraphNode() {
        override val kind: Int = 2
    }
}

/**
 * Assigns stable ids to all heap nodes reachable from snapshot roots.
 *
 * Object/exception classes retain identity equality, so these maps are
 * identity maps without relying on a JVM-only collection.
 */
private class SnapshotGraphEncoder(
    private val instance: Instance,
    private val externs: ExternEncoder,
    private val limits: SnapshotLimits,
) {
    private val nodes = mutableListOf<EncodedGraphNode>()
    private val gcIds = mutableMapOf<GcObject, Int>()
    private val exceptionIds = mutableMapOf<GuestException, Int>()
    private var heapValueCount = 0L

    val nodeCount: Int get() = nodes.size
    var hasConvertedRefs: Boolean = false
        private set

    fun scan(state: RuntimeStoreSnapshot) {
        state.instance.tables.forEachIndexed { tableIndex, table ->
            table.values().forEachIndexed { index, value ->
                scanValue(value, "table $tableIndex element $index", 0)
            }
        }
        state.instance.globals().forEachIndexed { index, value ->
            scanValue(value, "global $index", 0)
        }
        state.valueStack().forEachIndexed { index, value ->
            scanValue(value, "value stack slot $index", 0)
        }
        state.frames.forEachIndexed { frameIndex, frame ->
            frame.locals().forEachIndexed { index, value ->
                scanValue(value, "frame $frameIndex local $index", 0)
            }
            frame.controls.forEachIndexed { controlIndex, control ->
                control.caughtException?.let { exception ->
                    scanValue(
                        Value.Ref.Exn(exception),
                        "frame $frameIndex control $controlIndex caught exception",
                        0,
                    )
                }
            }
        }
        state.pendingImport?.arguments()?.forEachIndexed { index, value ->
            scanValue(value, "pending import argument $index", 0)
        }

        var nodeIndex = 0
        while (nodeIndex < nodes.size) {
            val node = nodes[nodeIndex]
            node.values.forEachIndexed { valueIndex, value ->
                scanValue(value, "heap node $nodeIndex value $valueIndex", 0)
            }
            nodeIndex++
        }
    }

    fun idOf(objectValue: GcObject, path: String): Int =
        gcIds[objectValue]
            ?: throw SnapshotStateException("$path refers to an unreachable GC object")

    fun idOf(exception: GuestException, path: String): Int =
        exceptionIds[exception]
            ?: throw SnapshotStateException("$path refers to an unreachable exception")

    fun checkReferenceNesting(nesting: Int, path: String) {
        if (nesting > limits.maxReferenceNesting) {
            throw SnapshotStateException(
                "$path reference nesting $nesting exceeds limit ${limits.maxReferenceNesting}",
            )
        }
    }

    fun writeTo(writer: SnapshotWriter) {
        writer.u32(nodes.size)
        nodes.forEach { node ->
            writer.u8(node.kind)
            writer.u8(0)
            writer.u16(0)
            writer.u32(node.typeOrTagIndex)
            writer.u32(node.values.size)
        }
        nodes.forEachIndexed { nodeIndex, node ->
            node.values.forEachIndexed { valueIndex, value ->
                writer.value(
                    value,
                    "heap node $nodeIndex value $valueIndex",
                    externs,
                    this,
                )
            }
        }
    }

    private fun scanValue(value: Value, path: String, nesting: Int) {
        checkReferenceNesting(nesting, path)
        when (value) {
            is Value.Ref.Extern, is Value.Ref.Host -> externs.scan(value, path)
            is Value.Ref.Gc -> value.value?.let { addGc(it, path) }
            is Value.Ref.Exn -> value.value?.let { addException(it, path) }
            is Value.Ref.AnyExtern -> {
                hasConvertedRefs = true
                scanValue(value.external, "$path converted externref", nesting + 1)
            }
            else -> Unit
        }
    }

    private fun addGc(objectValue: GcObject, path: String) {
        if (objectValue in gcIds) return
        ensureNodeCapacity(path)
        if (objectValue.owner !== instance) {
            throw SnapshotStateException(
                "$path contains a GC object owned by another or detached instance",
            )
        }
        val definition = instance.module.types.getOrNull(objectValue.typeIndex)
        val (kind, values) = when (objectValue) {
            is StructObject -> {
                if (definition !is StructType) {
                    throw SnapshotStateException(
                        "$path GC object type ${objectValue.typeIndex} is not a struct",
                    )
                }
                0 to objectValue.fields
            }
            is ArrayObject -> {
                if (definition !is ArrayType) {
                    throw SnapshotStateException(
                        "$path GC object type ${objectValue.typeIndex} is not an array",
                    )
                }
                1 to objectValue.elements
            }
        }
        addHeapValues(values.size, path)
        gcIds[objectValue] = nodes.size
        nodes += EncodedGraphNode.Gc(
            objectValue,
            objectValue.typeIndex,
            values,
            kind,
        )
    }

    private fun addException(exception: GuestException, path: String) {
        if (exception in exceptionIds) return
        ensureNodeCapacity(path)
        val identityIndex = instance.tags.indexOfFirst { it === exception.tag }
        if (identityIndex < 0) {
            throw SnapshotStateException(
                "$path contains an exception with a tag outside the snapshotted instance",
            )
        }
        if (exception.tagIndex != null && exception.tagIndex != identityIndex) {
            throw SnapshotStateException(
                "$path exception tag index ${exception.tagIndex} disagrees with nominal tag " +
                    "identity $identityIndex",
            )
        }
        addHeapValues(exception.arguments.size, path)
        exceptionIds[exception] = nodes.size
        nodes += EncodedGraphNode.Exception(
            exception,
            identityIndex,
            exception.arguments,
        )
    }

    private fun ensureNodeCapacity(path: String) {
        if (nodes.size >= limits.maxHeapObjects) {
            throw SnapshotStateException(
                "$path makes the heap graph exceed ${limits.maxHeapObjects} objects",
            )
        }
    }

    private fun addHeapValues(count: Int, path: String) {
        if (count < 0 || heapValueCount > limits.maxHeapValues.toLong() - count.toLong()) {
            throw SnapshotStateException(
                "$path makes the heap graph exceed ${limits.maxHeapValues} values",
            )
        }
        heapValueCount += count
    }
}

private sealed class DecodedGraphNode(
    open val values: MutableList<Value>,
) {
    class Gc(
        val objectValue: GcObject,
        override val values: MutableList<Value>,
    ) : DecodedGraphNode(values)

    class Exception(
        val exception: GuestException,
        override val values: MutableList<Value>,
    ) : DecodedGraphNode(values)
}

private class DecodedGraph(
    private val nodes: List<DecodedGraphNode>,
) {
    fun gc(id: Int, path: String): GcObject {
        val node = nodes.getOrNull(id)
            ?: throw SnapshotFormatException("$path refers to unknown heap node $id")
        return (node as? DecodedGraphNode.Gc)?.objectValue
            ?: throw SnapshotFormatException("$path heap node $id is not a GC object")
    }

    fun exception(id: Int, path: String): GuestException {
        val node = nodes.getOrNull(id)
            ?: throw SnapshotFormatException("$path refers to unknown heap node $id")
        return (node as? DecodedGraphNode.Exception)?.exception
            ?: throw SnapshotFormatException("$path heap node $id is not an exception")
    }

    companion object {
        fun empty(): DecodedGraph = DecodedGraph(emptyList())
    }
}

private class ExternEncoder(
    private val hooks: SnapshotHooks?,
    private val limits: SnapshotLimits,
) {
    private val keys = mutableMapOf<Int, ByteArray>()
    private val attempted = mutableSetOf<Int>()
    private val hostEntries = mutableListOf<HostEntry>()
    private val missing = mutableListOf<String>()
    private var missingCount = 0

    val hasExternRefs: Boolean
        get() = attempted.isNotEmpty() || hostEntries.isNotEmpty()
    val hasHostExternRefs: Boolean get() = hostEntries.isNotEmpty()

    fun scan(value: Value, path: String) {
        when (value) {
            is Value.Ref.Extern -> scanHandle(value.handle, path)
            is Value.Ref.Host -> value.value?.let { scanHost(it, path) }
            else -> Unit
        }
    }

    fun key(handle: Int): ByteArray =
        keys[handle]?.copyOf()
            ?: throw SnapshotStateException("externref handle $handle was not externalized")

    fun hostKey(value: Any): ByteArray =
        hostEntries.firstOrNull { it.value === value }?.key?.copyOf()
            ?: throw SnapshotStateException("host externref was not externalized")

    fun throwIfMissing() {
        if (missingCount == 0) return
        val suffix = if (missingCount > missing.size) {
            " (and ${missingCount - missing.size} more)"
        } else {
            ""
        }
        throw SnapshotStateException(
            "SnapshotHooks did not externalize $missingCount externref(s): " +
                missing.joinToString() + suffix,
        )
    }

    private fun scanHandle(handle: Int, path: String) {
        if (handle < 0 || !attempted.add(handle)) return
        val key = hooks?.externalizeExternRef(handle)
        if (key == null) {
            missing(path, "handle $handle")
            return
        }
        checkKey(key, path)
        keys[handle] = key.copyOf()
    }

    private fun scanHost(value: Any, path: String) {
        if (hostEntries.any { it.value === value }) return
        val key = hooks?.externalizeHostExternRef(value)
        if (key == null) {
            hostEntries += HostEntry(value, null)
            missing(path, "host object ${value::class.simpleName ?: "unknown"}")
            return
        }
        checkKey(key, path)
        hostEntries += HostEntry(value, key.copyOf())
    }

    private fun checkKey(key: ByteArray, path: String) {
        if (key.size > limits.maxExternKeyBytes) {
            throw SnapshotStateException(
                "$path externalization key has ${key.size} bytes; " +
                    "limit is ${limits.maxExternKeyBytes}",
            )
        }
    }

    private fun missing(path: String, detail: String) {
        missingCount++
        if (missing.size < 64) missing += "$path ($detail)"
    }

    private class HostEntry(
        val value: Any,
        val key: ByteArray?,
    )
}

private class ExternDecoder(private val hooks: SnapshotHooks?) {
    private val handleEntries = mutableListOf<HandleEntry>()
    private val hostEntries = mutableListOf<DecodedHostEntry>()
    private val missing = mutableListOf<String>()
    private var missingCount = 0

    fun rehydrate(key: ByteArray, path: String): Int {
        handleEntries.firstOrNull { it.key.contentEquals(key) }?.let { return it.handle }
        val handle = hooks?.rehydrateExternRef(key.copyOf())
        if (handle == null || handle < 0) {
            recordMissing(path, key)
            handleEntries += HandleEntry(key.copyOf(), 0)
            return 0
        }
        handleEntries += HandleEntry(key.copyOf(), handle)
        return handle
    }

    fun rehydrateHost(key: ByteArray, path: String): Any {
        hostEntries.firstOrNull { it.key.contentEquals(key) }?.let { return it.value }
        val value = hooks?.rehydrateHostExternRef(key.copyOf())
        if (value == null) {
            recordMissing(path, key)
            val placeholder = MissingHostExtern
            hostEntries += DecodedHostEntry(key.copyOf(), placeholder)
            return placeholder
        }
        hostEntries += DecodedHostEntry(key.copyOf(), value)
        return value
    }

    fun throwIfMissing() {
        if (missingCount == 0) return
        val suffix = if (missingCount > missing.size) {
            " (and ${missingCount - missing.size} more)"
        } else {
            ""
        }
        throw SnapshotStateException(
            "SnapshotHooks did not rehydrate $missingCount externref(s): " +
                missing.joinToString() + suffix,
        )
    }

    private fun recordMissing(path: String, key: ByteArray) {
        missingCount++
        if (missing.size < 64) {
            missing += "$path (key ${key.hexPreview()})"
        }
    }

    private class HandleEntry(val key: ByteArray, val handle: Int)
    private class DecodedHostEntry(val key: ByteArray, val value: Any)
    private object MissingHostExtern
}

private fun ByteArray.hexPreview(): String {
    val alphabet = "0123456789abcdef"
    val shown = minOf(size, 16)
    return buildString(shown * 2 + 3) {
        repeat(shown) { index ->
            val value = this@hexPreview[index].toInt() and 0xFF
            append(alphabet[value ushr 4])
            append(alphabet[value and 0x0F])
        }
        if (size > shown) append("...")
    }
}
