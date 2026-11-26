package ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import data.MediaService

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

    // ---- Alignment (Layout tab) ------------------------------------------
    // Three pseudo-text lines, anchored left / center / right — read
    // clearly as "align this paragraph" even at 16dp icon size.
    val AlignLeft: ImageVector by lazy {
        icon("AlignLeft", "M3,5h18v2H3V5zM3,11h13v2H3V11zM3,17h9v2H3V17z")
    }
    val AlignCenter: ImageVector by lazy {
        icon("AlignCenter", "M3,5h18v2H3V5zM5,11h14v2H5V11zM7,17h10v2H7V17z")
    }
    val AlignRight: ImageVector by lazy {
        icon("AlignRight", "M3,5h18v2H3V5zM8,11h13v2H8V11zM12,17h9v2h-9V17z")
    }
    val Styles: ImageVector by lazy { icon("Styles", "M5,4v3h5.5v12h3V7H19V4H5z") }
    val Heading1: ImageVector by lazy { icon("Heading1", "M19,3H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5C21,3.9 20.1,3 19,3zM12,15h-2V9H8V7h4V15z") }
    val Heading2: ImageVector by lazy { icon("Heading2", "M19,3H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5C21,3.9 20.1,3 19,3zM15,21h-2v-2h2V21zM13,17h2v-2h-2V17zM15,13h-2v-2h2V13zM11,9h2V7h-2V9zM15,5h-2v2h2V5zM19,9h-2v2h2V9z") }

    // ---- Insert ---------------------------------------------------------
    val Reference: ImageVector by lazy { icon("Reference", "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7c-1.71,0 -3.1,-1.39 -3.1,-3.1zM8,13h8v-2H8V13zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z") }
    val Book: ImageVector by lazy { icon("Book", "M18,2H6C4.9,2 4,2.9 4,4v16c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4C20,2.9 19.1,2 18,2zM6,4h5v8l-2.5,-1.5L6,12V4z") }

    // Chapter reference — an open book with a page marker. Distinct from
    // the Book icon (closed book) so the three granularity buttons are
    // visually distinguishable.
    val Chapter: ImageVector by lazy { icon("Chapter", "M20,3H6C4.9,3 4,3.9 4,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5C22,3.9 21.1,3 20,3zM12,6h7v2h-7V6zM12,10h7v2h-7V10zM12,14h5v2h-5V14zM7,5c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM7,9c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM7,13c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1z") }
    // Media link (YouTube / Spotify / …): a film-strip glyph, distinct
    // from the Bible Reference icon so the two insert buttons read as
    // different actions.
    val Media: ImageVector by lazy { icon("Media", "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4C2.9,4 2,4.9 2,6v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4H18z") }
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

    // ---- Home navigation -------------------------------------------------
    // Open book — the Bible reader.
    val Bible: ImageVector by lazy { icon("Bible", "M21,5c-1.11,-0.35 -2.33,-0.5 -3.5,-0.5 -1.95,0 -4.05,0.4 -5.5,1.5 -1.45,-1.1 -3.55,-1.5 -5.5,-1.5S2.45,4.9 1,6v14.65c0,0.25 0.25,0.5 0.5,0.5 0.1,0 0.15,-0.05 0.25,-0.05C3.1,20.45 5.05,20 6.5,20c1.95,0 4.05,0.4 5.5,1.5 1.35,-0.85 3.8,-1.5 5.5,-1.5 1.65,0 3.35,0.3 4.75,1.05 0.1,0.05 0.15,0.05 0.25,0.05 0.25,0 0.5,-0.25 0.5,-0.5V6C22.4,5.55 21.75,5.25 21,5z") }
    // Document with text lines — the notes editor.
    val Notes: ImageVector by lazy { icon("Notes", "M19,3H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5C21,3.9 20.1,3 19,3zM14,17H7v-2h7V17zM17,13H7v-2h10V13zM17,9H7V7h10V9z") }
    // Bar chart — reading statistics.
    val Statistics: ImageVector by lazy { icon("Statistics", "M5,9.2h3V19H5V9.2zM10.6,5h2.8v14h-2.8V5zM16.2,13H19v6h-2.8V13z") }
    // "文A" translate glyph — word study across the original languages.
    val WordStudy: ImageVector by lazy { icon("WordStudy", "M12.87,15.07l-2.54,-2.51 0.03,-0.03c1.74,-1.94 2.98,-4.17 3.71,-6.53H17V4h-7V2H8v2H1v1.99h11.17C11.5,7.92 10.44,9.75 9,11.35 8.07,10.32 7.3,9.19 6.69,8h-2c0.73,1.63 1.73,3.17 2.98,4.56l-5.09,5.02L4,19l5,-5 3.11,3.11 0.76,-2.04zM18.5,10h-2L12,22h2l1.12,-3h4.75L21,22h2L18.5,10zM15.88,17L17.5,12.67 19.12,17h-3.24z") }
    // Bookmark ribbon — saved verse collections.
    val Collections: ImageVector by lazy { icon("Collections", "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5C19,3.9 18.1,3 17,3z") }
    // Gear — settings.
    val Settings: ImageVector by lazy { icon("Settings", "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z") }
    // Power button — quit the app.
    val Quit: ImageVector by lazy { icon("Quit", "M13,3h-2v10h2V3zM17.83,5.17l-1.42,1.42C17.99,7.86 19,9.81 19,12c0,3.87 -3.13,7 -7,7s-7,-3.13 -7,-7c0,-2.19 1.01,-4.14 2.58,-5.42L6.17,5.17C4.23,6.82 3,9.26 3,12c0,4.97 4.03,9 9,9s9,-4.03 9,-9c0,-2.74 -1.23,-5.18 -3.17,-6.83z") }

    // ---- Settings controls ----------------------------------------------
    // Speaker — "test click sound".
    val Sound: ImageVector by lazy { icon("Sound", "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05C15.48,15.29 16.5,13.77 16.5,12zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77S18.01,4.14 14,3.23z") }
    // Numeric steppers (− / +).
    val Minus: ImageVector by lazy { icon("Minus", "M19,13H5v-2h14V13z") }
    val Plus: ImageVector by lazy { icon("Plus", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6V13z") }
    // Dropdown / expandable rows: chevrons for collapsed ▸ / expanded ▾.
    val ChevronDown: ImageVector by lazy { icon("ChevronDown", "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6z") }
    val ChevronRight: ImageVector by lazy { icon("ChevronRight", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z") }
    // Pencil — edit an entry.
    val Edit: ImageVector by lazy { icon("Edit", "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z") }
    // Sheet of paper — a note file result row.
    val Document: ImageVector by lazy { icon("Document", "M6,2C4.9,2 4.01,2.9 4.01,4L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6H6zM13,9V3.5L18.5,9H13z") }
    // Folder — a notes sidebar section header.
    val Folder: ImageVector by lazy { icon("Folder", "M10,4H4C2.9,4 2.01,4.9 2.01,6L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8L10,4z") }
    // Vertical ellipsis — the row-actions menu trigger.
    val More: ImageVector by lazy { icon("More", "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z") }

    // ---- Media playback controls --------------------------------------
    // Circular arrows — loop / repeat the current media.
    val Loop: ImageVector by lazy {
        icon("Loop", "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4z")
    }
    // Box with an arrow leaving it — open the media on its own service
    // page (YouTube / Vimeo / …) in the default browser.
    val OpenExternal: ImageVector by lazy {
        icon("OpenExternal", "M19,19H5V5h7V3H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2V19zM14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3H14z")
    }

    // ---- Media service glyphs ------------------------------------------
    // Monochrome equivalents of the colorful service emojis, so the media
    // cards / popup / mini player / dialogs share the main menu's
    // restrained single-color icon language instead of drawing the eye
    // with multicolor glyphs. Tinted by the surrounding Icon's color, so
    // they adapt to their container like every other RibbonIcons glyph.
    // Play triangle — YouTube (and the generic "playable media" glyph).
    val MediaYouTube: ImageVector by lazy { icon("MediaYouTube", "M8,5v14l11,-7z") }
    // Film frame — Vimeo (a video service, like the editor's Media glyph).
    val MediaVimeo: ImageVector by lazy {
        icon("MediaVimeo", "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4C2.9,4 2,4.9 2,6v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4H18z")
    }
    // Music note — Spotify.
    val MediaSpotify: ImageVector by lazy {
        icon("MediaSpotify", "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3H12z")
    }
    // Audio waves — SoundCloud.
    val MediaSoundCloud: ImageVector by lazy {
        icon("MediaSoundCloud", "M7,18h2V6H7V18zM3,18h2V10H3V18zM11,18h2V4h-2V18zM15,18h2V8h-2V18zM19,18h2V2h-2V18z")
    }
    // Chain link — generic web link.
    val MediaLink: ImageVector by lazy {
        icon("MediaLink", "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7c-1.71,0 -3.1,-1.39 -3.1,-3.1zM8,13h8v-2H8V13zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z")
    }
    // Document — local file.
    val MediaFile: ImageVector by lazy {
        icon("MediaFile", "M14,2H6C4.9,2 4.01,2.9 4.01,4L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8L14,2zM16,18H8v-2h8V18zM16,14H8v-2h8V14zM13,9V3.5L18.5,9H13z")
    }
    // Plain play triangle — MiniPlayer fallback / generic playable media
    // (same geometry as the YouTube glyph).
    val MediaPlay: ImageVector by lazy { MediaYouTube }
    // Person — a channel in the media search suggestions.
    val MediaChannel: ImageVector by lazy {
        icon("MediaChannel", "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z")
    }


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


/** Monochrome service glyph rendered wherever the media UI shows a
 *  service (cards, popup, mini player, insert dialog) — the muted,
 *  single-color counterpart to the colorful emojis it replaces. Lives in
 *  the UI layer because icons are a presentation concern; the data layer
 *  keeps no display glyphs. */
internal val MediaService.icon: ImageVector
    get() = when (this) {
        MediaService.YOUTUBE -> RibbonIcons.MediaYouTube
        MediaService.VIMEO -> RibbonIcons.MediaVimeo
        MediaService.SPOTIFY -> RibbonIcons.MediaSpotify
        MediaService.SOUNDCLOUD -> RibbonIcons.MediaSoundCloud
        MediaService.LINK -> RibbonIcons.MediaLink
        MediaService.FILE -> RibbonIcons.MediaFile
    }
