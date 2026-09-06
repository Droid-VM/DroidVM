# Vendored: multiplatform-markdown-renderer

Upstream: https://github.com/mikepenz/multiplatform-markdown-renderer
Version:  v0.45.0
Licence:  Apache-2.0 (see LICENSE in this directory)

This is third-party source, kept here rather than pulled as a Maven artifact. Do not put the
project's SPDX header on these files: they are not ours and stay under their own licence.

## What was taken

  * `multiplatform-markdown-renderer/src/commonMain/kotlin` -- the renderer
  * `multiplatform-markdown-renderer-m3/src/commonMain/kotlin` -- the Material 3 bindings

Nothing else: the Coil image loaders, the Material 2 bindings and the syntax-highlighting module
are not used here, and neither are the js/wasm/native source sets.

## What was changed

One file. `utils/MarkdownLogger.kt` declares `internal expect fun platformLog` upstream, with an
`actual` per platform; `expect`/`actual` needs the Kotlin Multiplatform plugin, and this is a
plain Android module, so the Android implementation is inlined there and the androidMain file is
not copied. The change is marked in place.

## Why it is vendored

The published artifacts are built with a newer Kotlin than AGP's built-in Kotlin plugin provides,
and a compiler will not read metadata from a newer one -- which pinned us to the last release
built with a matching Kotlin (0.38.1). As source it simply compiles with whatever Kotlin the
build has, so the version we track is a choice rather than a constraint.

## Updating

Replace the two directories above from a fresh tag, re-apply the MarkdownLogger change, and build.
