package usb

/**
 * Required upstream engine is not linked. CI must fetch third_party sources and
 * enable the matching USB_WITH_* CMake option; local builds keep engines OFF.
 *
 * [cause] is the original [UnsatisfiedLinkError] (or null when constructed without one).
 */
class EngineMissingException(
    engine: String,
    cause: Throwable? = null,
) : Exception(
    "$engine engine not linked; fetch pinned sources via scripts/fetch-third-party.sh in CI",
    cause,
)

/**
 * Shared native-engine gate: map only [UnsatisfiedLinkError] to
 * [EngineMissingException] with [engine] name and the original link error as cause.
 * Every other throwable passes through unchanged.
 */
inline fun <T> requireLinkedEngine(engine: String, op: () -> T): T =
    try {
        op()
    } catch (failure: UnsatisfiedLinkError) {
        throw EngineMissingException(engine, failure)
    }
