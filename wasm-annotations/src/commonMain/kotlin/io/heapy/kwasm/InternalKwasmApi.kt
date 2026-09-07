package io.heapy.kwasm

/**
 * Marks kwasm API that is public only because another kwasm module needs it.
 *
 * These declarations carry no compatibility guarantee at all: they exist to
 * let `:snapshot`, `:tck`, `:benchmarks` and the other first-party modules
 * reach across a module boundary, and they may be renamed, reshaped, or
 * removed in any release. The marker is error-level so that reaching one from
 * outside kwasm is a deliberate act rather than an accident.
 */
@RequiresOptIn(
    message =
        "This kwasm API is an implementation detail shared between kwasm " +
            "modules. It has no compatibility guarantee and may change or " +
            "disappear in any release.",
    level = RequiresOptIn.Level.ERROR,
)
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FIELD,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.TYPEALIAS,
)
public annotation class InternalKwasmApi
