package com.nuvio.app.features.downloads

internal fun matchesPersistedResumeIdentity(
    stableContentIdentity: String?,
    persistedIdentity: String?,
    hasAriaControlState: Boolean,
): Boolean =
    !stableContentIdentity.isNullOrBlank() &&
        stableContentIdentity == persistedIdentity &&
        hasAriaControlState

internal fun matchesActiveResumeIdentity(
    stableContentIdentity: String?,
    activeIdentity: String?,
    persistedIdentity: String?,
): Boolean =
    !stableContentIdentity.isNullOrBlank() &&
        stableContentIdentity == activeIdentity &&
        stableContentIdentity == persistedIdentity