package io.heapy.kwasm

/**
 * Marks kwasm API that may change incompatibly before the project reaches 1.0.
 *
 * Opt in at the narrowest practical scope with
 * `@OptIn(ExperimentalKwasmApi::class)`. The marker is warning-level during
 * the pre-1.0 period so upgrades can surface unstable usage without making an
 * existing build fail solely because it adopted this marker.
 */
@RequiresOptIn(
    message =
        "This kwasm API is pre-1.0 and may change without a compatibility " +
            "guarantee. Review the release notes before upgrading.",
    level = RequiresOptIn.Level.WARNING,
)
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.TYPEALIAS,
)
public annotation class ExperimentalKwasmApi
