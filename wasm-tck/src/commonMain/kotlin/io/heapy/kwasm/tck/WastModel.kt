package io.heapy.kwasm.tck

/** Parsed output of one WABT `wast2json` invocation. */
public data class WastScript(
    public val sourceFilename: String,
    public val commands: List<WastCommand>,
) {
    public companion object {
        public fun parse(json: String): WastScript = WastJson.decode(Json.parse(json))
    }
}

/** Every command currently emitted by WABT for core-spec scripts. */
public sealed class WastCommand {
    public abstract val line: Int

    public data class Module(
        override val line: Int,
        public val filename: String,
        public val name: String? = null,
        public val moduleType: String? = null,
    ) : WastCommand()

    public data class Register(
        override val line: Int,
        public val name: String?,
        public val alias: String,
    ) : WastCommand()

    public data class Action(
        override val line: Int,
        public val action: WastAction,
    ) : WastCommand()

    public data class AssertReturn(
        override val line: Int,
        public val action: WastAction,
        public val expected: List<WastValue>,
    ) : WastCommand()

    public data class AssertTrap(
        override val line: Int,
        public val action: WastAction,
        public val text: String,
    ) : WastCommand()

    public data class AssertExhaustion(
        override val line: Int,
        public val action: WastAction,
        public val text: String,
    ) : WastCommand()

    public data class AssertException(
        override val line: Int,
        public val action: WastAction,
        public val text: String? = null,
    ) : WastCommand()

    public data class AssertMalformed(
        override val line: Int,
        public val filename: String,
        public val text: String,
        public val moduleType: String? = null,
    ) : WastCommand()

    public data class AssertInvalid(
        override val line: Int,
        public val filename: String,
        public val text: String,
        public val moduleType: String? = null,
    ) : WastCommand()

    public data class AssertUnlinkable(
        override val line: Int,
        public val filename: String,
        public val text: String,
    ) : WastCommand()

    public data class AssertUninstantiable(
        override val line: Int,
        public val filename: String,
        public val text: String,
    ) : WastCommand()
}

public sealed class WastAction {
    public abstract val module: String?

    public data class Invoke(
        override val module: String?,
        public val field: String,
        public val arguments: List<WastValue>,
    ) : WastAction()

    public data class Get(
        override val module: String?,
        public val field: String,
    ) : WastAction()
}

/** A WABT constant. Float payloads are raw IEEE bit patterns or NaN classes. */
public data class WastValue(
    public val type: String,
    public val value: String? = null,
    public val laneType: String? = null,
    public val lanes: List<String> = emptyList(),
)

/** A structurally valid JSON file that is not a `wast2json` manifest. */
public class WastManifestException(message: String) : IllegalArgumentException(message)

private object WastJson {
    fun decode(value: JsonValue): WastScript {
        val root = value.objectValue("manifest")
        return WastScript(
            sourceFilename = root.requiredString("source_filename"),
            commands = root.requiredArray("commands").mapIndexed { index, command ->
                decodeCommand(command.objectValue("commands[$index]"))
            },
        )
    }

    private fun decodeCommand(command: JsonValue.Object): WastCommand {
        val type = command.requiredString("type")
        val line = command.int("line") ?: 0
        return when (type) {
            "module" -> WastCommand.Module(
                line,
                command.requiredString("filename"),
                command.string("name"),
                command.string("module_type"),
            )
            "register" -> WastCommand.Register(
                line,
                command.string("name"),
                command.requiredString("as"),
            )
            "action" -> WastCommand.Action(line, decodeAction(command.requiredObject("action")))
            "assert_return", "assert_return_canonical_nan", "assert_return_arithmetic_nan" ->
                WastCommand.AssertReturn(
                    line,
                    decodeAction(command.requiredObject("action")),
                    command.array("expected").orEmpty().map(::decodeValue),
                )
            "assert_trap" -> WastCommand.AssertTrap(
                line,
                decodeAction(command.requiredObject("action")),
                command.requiredString("text"),
            )
            "assert_exhaustion" -> WastCommand.AssertExhaustion(
                line,
                decodeAction(command.requiredObject("action")),
                command.requiredString("text"),
            )
            "assert_exception" -> WastCommand.AssertException(
                line,
                decodeAction(command.requiredObject("action")),
                command.string("text"),
            )
            "assert_malformed" -> WastCommand.AssertMalformed(
                line,
                command.requiredString("filename"),
                command.requiredString("text"),
                command.string("module_type"),
            )
            "assert_invalid" -> WastCommand.AssertInvalid(
                line,
                command.requiredString("filename"),
                command.requiredString("text"),
                command.string("module_type"),
            )
            "assert_unlinkable" -> WastCommand.AssertUnlinkable(
                line,
                command.requiredString("filename"),
                command.requiredString("text"),
            )
            "assert_uninstantiable" -> WastCommand.AssertUninstantiable(
                line,
                command.requiredString("filename"),
                command.requiredString("text"),
            )
            else -> throw WastManifestException("unsupported wast2json command '$type' at line $line")
        }
    }

    private fun decodeAction(action: JsonValue.Object): WastAction {
        return when (val type = action.requiredString("type")) {
            "invoke" -> WastAction.Invoke(
                module = action.string("module"),
                field = action.requiredString("field"),
                arguments = action.array("args").orEmpty().map(::decodeValue),
            )
            "get" -> WastAction.Get(
                module = action.string("module"),
                field = action.requiredString("field"),
            )
            else -> throw WastManifestException("unsupported wast2json action '$type'")
        }
    }

    private fun decodeValue(value: JsonValue): WastValue {
        val objectValue = value.objectValue("constant")
        val rawValue = objectValue["value"]
        return WastValue(
            type = objectValue.requiredString("type"),
            value = (rawValue as? JsonValue.StringValue)?.value
                ?: (rawValue as? JsonValue.NumberValue)?.source,
            laneType = objectValue.string("lane_type"),
            lanes = (rawValue as? JsonValue.Array)?.values.orEmpty().mapIndexed { index, lane ->
                when (lane) {
                    is JsonValue.StringValue -> lane.value
                    is JsonValue.NumberValue -> lane.source
                    else -> throw WastManifestException("v128 lane $index is not a scalar")
                }
            },
        )
    }
}

private fun JsonValue.objectValue(context: String): JsonValue.Object =
    this as? JsonValue.Object ?: throw WastManifestException("$context must be an object")

private fun JsonValue.Object.requiredObject(name: String): JsonValue.Object =
    this[name]?.objectValue(name) ?: throw WastManifestException("missing object '$name'")

private fun JsonValue.Object.requiredArray(name: String): List<JsonValue> =
    array(name) ?: throw WastManifestException("missing array '$name'")

private fun JsonValue.Object.array(name: String): List<JsonValue>? = when (val value = this[name]) {
    null, JsonValue.Null -> null
    is JsonValue.Array -> value.values
    else -> throw WastManifestException("'$name' must be an array")
}

private fun JsonValue.Object.requiredString(name: String): String =
    string(name) ?: throw WastManifestException("missing string '$name'")

private fun JsonValue.Object.string(name: String): String? = when (val value = this[name]) {
    null, JsonValue.Null -> null
    is JsonValue.StringValue -> value.value
    else -> throw WastManifestException("'$name' must be a string")
}

private fun JsonValue.Object.int(name: String): Int? = when (val value = this[name]) {
    null, JsonValue.Null -> null
    is JsonValue.NumberValue -> value.source.toIntOrNull()
        ?: throw WastManifestException("'$name' is outside the Int range")
    else -> throw WastManifestException("'$name' must be a number")
}
