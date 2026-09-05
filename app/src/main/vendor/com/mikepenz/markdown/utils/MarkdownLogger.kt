package com.mikepenz.markdown.utils

import androidx.compose.runtime.snapshots.Snapshot

object MarkdownLogger {
    /**
     * Whether to print debug log statements to the relevant system logger. Do not build release
     * artifacts with this enabled. It's purely for debugging purposes.
     */
    var enabled: Boolean = false

    fun d(tag: String, message: () -> String) {
        d(tag = tag, throwable = null, message = message)
    }

    fun d(tag: String, throwable: Throwable?, message: () -> String) {
        if (enabled) {
            Snapshot.withoutReadObservation {
                platformLog(
                    tag = tag,
                    message = buildString {
                        append(message())
                        if (throwable != null) {
                            append(". Throwable: ")
                            append(throwable)
                        }
                    },
                )
            }
        }
    }
}

// Vendored change: upstream declares this `expect` and supplies one `actual` per platform. This
// tree is Android-only and not a multiplatform module, so the Android implementation
// (androidMain/MarkdownLogger.android.kt upstream) is inlined here instead.
internal fun platformLog(tag: String, message: String) {
    android.util.Log.d(tag, message)
}