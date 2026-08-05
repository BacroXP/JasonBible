package ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Custom icon set for the editor ribbon, header and related controls.
 *
 * Every icon is a 24×24 filled vector defined here from path data, so
 * the app has one consistent, self-contained icon language — no heavy
 * material-icons-extended dependency, and every glyph is tinted
 * automatically by the surrounding `Icon`/color scheme.
 *
 * The path strings are hand-checked against the Material icon geometry
 * (24×24 viewport). They are parsed lazily so the set only costs what it
 * is actually used.
 */
object RibbonIcons {

    // ---- History ---------------------------------------------------------
    val Undo: ImageVector by lazy { icon("Undo", "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z") }
    val Redo: ImageVector by lazy { icon("Redo", "M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16c1.05,-3.19 4.05,-5.5 7.6,-5.5 1.95,0 3.73,0.72 5.12,1.88L13,16h9V7L18.4,10.6z") }

    // ---- Clipboard -------------------------------------------------------
    val Cut: ImageVector by lazy { icon("Cut", "M9.64,7.64c0.23,-0.5 0.36,-1.05 0.36,-1.64 0,-2.21 -1.79,-4 -4,-4S2,3.79 2,6s1.79,4 4,4c0.59,0 1.14,-0.13 1.64,-0.36L10,12l-2.36,2.36C7.14,14.13 6.59,14 6,14c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4c0,-0.59 -0.13,-1.14 -0.36,-1.64L12,14l7,7h3v-1L9.64,7.64zM6,8c-1.1,0 -2,-0.89 -2,-2s0.9,-2 2,-2 2,0.89 2,2 -0.9,2 -2,2zM6,20c-1.1,0 -2,-0.89 -2,-2s0.9,-2 2,-2 2,0.89 2,2 -0.9,2 -2,2zM12,12.5c-0.28,0 -0.5,-0.22 -0.5,-0.5s0.22,-0.5 0.5,-0.5 0.5,0.22 0.5,0.5 -0.22,0.5 -0.5,0.5zM19,3l-6,6 2,2 7,-7V3z") }
    val Copy: ImageVector by lazy { icon("Copy", "M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1zM19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7C21,5.9 20.1,5 19,5zM19,21H8V7h11V21z") }
    val Paste: ImageVector by lazy { icon("Paste", "M19,2h-4.18C14.4,0.84 13.3,0 12,0c-1.3,0 -2.4,0.84 -2.82,2H5C3.9,2 3,2.9 3,4v16c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V4C21,2.9 20.1,2 19,2zM12,2c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM19,20H5V4h2v3h10V4h2V20z") }

    // ---- Edit -----------------------------------------------------------
    val Find: ImageVector by lazy { icon("Find", "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z") }
    val SelectAll: ImageVector by lazy { icon("SelectAll", "M3,5h2V3C3.9,3 3,3.9 3,5zM3,13h2v-2H3V13zM7,21h2v-2H7V21zM3,9h2V7H3V9zM13,3h-2v2h2V3zM19,3v2h2C21,3.9 20.1,3 19,3zM5,3v2h2C7,3.9 6.1,3 5,3zM9,3v2h2V3H9zM3,17h2v-2H3V17zM19,13h2v-2h-2V13zM17,21h2v-2h-2V21zM13,21h2v-2h-2V21zM19,17h2v-2h-2V17zM9,21h2v-2H9V21zM21,5h-2v2h2V5zM7,7v10h10V7H7zM17,17H7V7h10V17z") }

    // ---- Style / Format --------------------------------------------------
    val ClearFormat: ImageVector by lazy { icon("ClearFormat", "M3.27,5L2,6.27l6.97,6.97L6.5,19h3.76l1.5,-3.5 3.03,3.03L17.73,19 19,17.73 3.27,5zM13.97,14.38l-3.13,-3.13L14.31,7.37c0.78,-0.78 2.05,-0.78 2.83,0l2.49,2.49c0.78,0.78 0.78,2.05 0,2.83l-5.66,5.69z") }
    val Quote: ImageVector by lazy { icon("Quote", "M6,17h3l2,-4V7H5v6h3L6,17zM14,17h3l2,-4V7h-6v6h3L14,17z") }
    val BulletList: ImageVector by lazy { icon("BulletList", "M4,10.5c-0.83,0 -1.5,0.67 -1.5,1.5s0.67,1.5 1.5,1.5 1.5,-0.67 1.5,-1.5 -0.67,-1.5 -1.5,-1.5zM4,4.5C3.17,4.5 2.5,5.17 2.5,6S3.17,7.5 4,7.5 5.5,6.83 5.5,6 4.83,4.5 4,4.5zM4,16.5c-0.83,0 -1.5,0.68 -1.5,1.5s0.68,1.5 1.5,1.5 1.5,-0.68 1.5,-1.5 -0.67,-1.5 -1.5,-1.5zM7,19h14v-2H7V19zM7,13h14v-2H7V13zM7,5v2h14V5H7z") }
    val NumberedList: ImageVector by lazy { icon("NumberedList", "M2,17h2v0.5H3v1h1v0.5H2v1h3v-4H2V17zM3,8h1V4H2v1h1V8zM2,11h1.8L2,13.1v0.9h3v-1H3.2L5,10.9V10H2V11zM7,5v2h14V5H7zM7,19h14v-2H7V19zM7,13h14v-2H7V13z") }
    val AutoList: ImageVector by lazy { icon("AutoList", "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z") }
    val Direction: ImageVector by lazy { icon("Direction", "M6.99,11L3,15l3.99,4v-3H14v-2H6.99V11zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z") }
    val Styles: ImageVector by lazy { icon("Styles", "M5,4v3h5.5v12h3V7H19V4H5z") }
    val Heading1: ImageVector by lazy { icon("Heading1", "M19,3H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5C21,3.9 20.1,3 19,3zM12,15h-2V9H8V7h4V15z") }
    val Heading2: ImageVector by lazy { icon("Heading2", "M19,3H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5C21,3.9 20.1,3 19,3zM15,21h-2v-2h2V21zM13,17h2v-2h-2V17zM15,13h-2v-2h2V13zM11,9h2V7h-2V9zM15,5h-2v2h2V5zM19,9h-2v2h2V9z") }

    // ---- Insert ---------------------------------------------------------
    val Reference: ImageVector by lazy { icon("Reference", "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7c-1.71,0 -3.1,-1.39 -3.1,-3.1zM8,13h8v-2H8V13zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z") }
    val Book: ImageVector by lazy { icon("Book", "M18,2H6C4.9,2 4,2.9 4,4v16c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4C20,2.9 19.1,2 18,2zM6,4h5v8l-2.5,-1.5L6,12V4z") }
    val Date: ImageVector by lazy { icon("Date", "M9,11H7v2h2V11zM13,11h-2v2h2V11zM17,11h-2v2h2V11zM19,4h-1V2h-2v2H8V2H6v2H5C3.89,4 3.01,4.9 3.01,6L3,20c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V6C21,4.9 20.1,4 19,4zM19,20H5V9h14V20z") }

    // ---- Find / replace bar --------------------------------------------
    val PrevMatch: ImageVector by lazy { icon("PrevMatch", "M7.41,15.41L12,10.83l4.59,4.58L18,14l-6,-6 -6,6z") }
    val NextMatch: ImageVector by lazy { icon("NextMatch", "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6z") }
    val Close: ImageVector by lazy { icon("Close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z") }
    val Replace: ImageVector by lazy { icon("Replace", "M9.01,14H2v2h7.01v3L13,15l-3.99,-4V14zM14.99,10v-3H22V5h-7.01V2L11,6l3.99,4z") }
    val ReplaceAll: ImageVector by lazy { icon("ReplaceAll", "M18,7l-1.41,-1.41 -6.34,6.34 1.41,1.41L18,7zM22.24,5.59L11.66,16.17l-2.83,-2.83 -1.41,1.41 4.24,4.24L23.66,7L22.24,5.59zM0.41,16.24L4.65,20l1.41,-1.41L1.83,14.83 0.41,16.24zM12.34,15.17l1.41,-1.41 -1.41,-1.41 -1.41,1.41L12.34,15.17z") }

    // ---- Header ---------------------------------------------------------
    val Back: ImageVector by lazy { icon("Back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20V11z") }
    val Export: ImageVector by lazy { icon("Export", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z") }
    val Save: ImageVector by lazy { icon("Save", "M17,3H5C3.89,3 3,3.9 3,5v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V7L17,3zM12,19c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3zM15,9H5V5h10V9z") }
    val New: ImageVector by lazy { icon("New", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6V13z") }
    val Delete: ImageVector by lazy { icon("Delete", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6V19zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z") }


    private fun icon(name: String, pathData: String): ImageVector {
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black)
        ).build()
    }
}
