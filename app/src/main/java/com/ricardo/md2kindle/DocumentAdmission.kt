package com.ricardo.md2kindle

/**
 * Pure admission rules for documents received from Android.
 *
 * Providers are inconsistent: the same Markdown file can arrive as text/markdown,
 * text/plain, application/octet-stream or without a MIME type. Generic binary
 * streams are therefore accepted only when their displayed filename identifies a
 * supported text document.
 */
object DocumentAdmission {
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_SEND = "android.intent.action.SEND"

    private val markdownMimeTypes = setOf(
        "text/markdown",
        "text/x-markdown",
        "text/md",
        "application/markdown",
        "application/x-markdown",
    )

    private val supportedExtensions = setOf("md", "markdown", "txt")
    private val supportedSchemes = setOf("content", "file")

    fun acceptsUri(
        action: String?,
        mimeType: String?,
        scheme: String?,
        displayName: String?,
    ): Boolean {
        if (action != ACTION_VIEW && action != ACTION_SEND) return false
        if (scheme?.lowercase() !in supportedSchemes) return false

        val normalizedMime = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        val supportedName = hasSupportedExtension(displayName)

        return when {
            normalizedMime in markdownMimeTypes -> true
            normalizedMime == "text/plain" -> displayName.isNullOrBlank() || supportedName
            normalizedMime == "application/octet-stream" -> supportedName
            normalizedMime.isNullOrBlank() -> supportedName
            else -> false
        }
    }

    fun acceptsSharedText(action: String?, mimeType: String?): Boolean {
        if (action != ACTION_SEND) return false
        val normalizedMime = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        return normalizedMime == "text/plain" || normalizedMime in markdownMimeTypes
    }

    fun hasSupportedExtension(displayName: String?): Boolean {
        val cleanName = displayName
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.substringAfterLast('/')
            ?.trim()
            .orEmpty()
        val extension = cleanName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
        return extension in supportedExtensions
    }
}
