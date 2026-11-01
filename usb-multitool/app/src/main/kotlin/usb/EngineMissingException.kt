package usb

/**
 * Required upstream engine is not linked. CI must fetch third_party sources and
 * enable the matching USB_WITH_* CMake option; local builds keep engines OFF.
 */
class EngineMissingException(engine: String) : Exception(
    "$engine engine not linked; fetch pinned sources via scripts/fetch-third-party.sh in CI",
)
