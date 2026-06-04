# PDF Inspector

A DevTools-style inspector and surgical editor for PDFs on Android. Open a
document, see its content stream as a navigable tree, select any element — a
text block, a vector path, an image — from the canvas or the tree, delete it,
and save a copy. Built with Kotlin + Jetpack Compose on top of PdfBox-Android.

## Status — core loop complete

- **View** — render with pinch-zoom and pan
- **Inspect** — content stream parsed into a draw-event tree (`q/Q` groups,
  `BT…ET` text, paths, images) with exact token ranges
- **Select** — tap on the canvas or tap a tree row; selection is two-way bound
  and drawn as a translucent, bordered highlight
- **Friendly / Raw** — toggle between decoded labels and the raw operators
- **Delete** — removes the element's exact tokens and rewrites the stream
- **Save** — writes a new copy; the original file is never touched

## Architecture

One engine powers viewing, the tree, canvas selection, and editing.

- `engine/` — pure Kotlin, no Android or Compose dependencies
  - `ContentStreamEngine` — tokenizes the page (`PDFStreamParser`), tracks the
    transformation matrix, and builds the `DrawNode` tree with bounds + token
    ranges
  - `ElementEditor` — drops a token range and rewrites via `ContentStreamWriter`
  - `Geometry` (`Affine`, `Bounds`), `DrawNode`
- `ui/` — Compose: `PdfCanvas` (gestures + highlight), `InspectorPane` (tree),
  `PageTransform` (PDF user space → bitmap pixels)
- `PdfDocumentViewModel` — load / render / parse / select / delete / save state

## Build and run

```
JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest
```

The system Java 21 here is JRE-only, so point `JAVA_HOME` at a full JDK such as
Android Studio's bundled JBR. Android Studio handles this automatically.

Toolchain: Kotlin 2.0, AGP 8.12, Gradle 8.13, minSdk 26.

## Known limitations (v1)

- Text bounding boxes are approximate (position exact, width estimated); tree
  selection and deletion are exact regardless.
- Deleting a bare image leaves its preceding `cm`; delete the parent Group to
  remove it cleanly.
- Rotated-page (90/270) canvas highlights are best-effort; the tree is exact.
- Type0 / CJK text previews may show placeholders.

## Roadmap

- Drag-to-move (`q/cm/Q` wrap for text and shapes; matrix edit for images)
- Resize images via the placement matrix
- Hide toggle (non-destructive) before delete
- Undo / redo, multi-page thumbnails
