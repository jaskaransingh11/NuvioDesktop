package com.nuvio.app.features.downloads

/**
 * Stable identity for a debrid-resolved torrent file.
 *
 * A signed HTTPS URL is intentionally excluded because it can expire or rotate.
 * infoHash + fileIdx identifies a concrete file inside a concrete torrent; size
 * and normalized filename add fail-closed consistency checks against provider or
 * resolver mistakes. If any strong torrent identity field is absent, resuming a
 * non-empty partial across a new process/source remains forbidden.
 */
internal fun DownloadItem.stableDownloadContentIdentity(): String? {
    val source = sourceResolve ?: return null
    if (!source.type.equals("torrent", ignoreCase = true)) return null

    val infoHash = source.infoHash
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.matches(Regex("[0-9a-f]{40}|[0-9a-f]{64}")) }
        ?: return null
    val fileIndex = source.fileIdx?.takeIf { it >= 0 } ?: return null
    val size = totalBytes?.takeIf { it > 0L } ?: return null
    val filename = source.filename
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
        ?: return null

    return "torrent:$infoHash|file:$fileIndex|size:$size|name:$filename"
}