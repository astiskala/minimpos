package app.minimpos.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The fixed spacing scale for gaps and padding that do not depend on the screen size; `.stitch/DESIGN.md` names each
 * step. Screen-dependent gaps come from [Dimens] instead (`screenPadding`, `spacing`, `cardPadding`).
 */
object Spacing {
    /** Between a label and the text under it, and around dense inline content. */
    val XSmall = 4.dp

    /** Between an icon and its text, and inside compact notices. */
    val Small = 8.dp

    /** Between a spinner and a button label, and between a row's content and its control. */
    val Medium = 12.dp

    /** Around empty-state content on compact screens and above a section's first element. */
    val Large = 16.dp

    /** Above a section header on regular screens and around the content of a picker dialog. */
    val Section = 20.dp

    /** Before an empty state's action and between large, separate blocks. */
    val XLarge = 24.dp

    /** Around empty-state content on regular screens. */
    val XXLarge = 32.dp
}

/** Sizes of icons drawn inside text, buttons and rows. */
object IconSize {
    /** In compact notices and the tick of a finished step. */
    val Small = 16.dp

    /** Beside a button label. */
    val Inline = 20.dp

    /** The numbered marker of a step. */
    val Standard = 24.dp

    /** The artwork of a Home payment tile. */
    val Tile = 32.dp

    /** Empty state on compact screens, and the badge of a result dialog. */
    val Large = 40.dp
}

/** Width of the outline of secondary buttons, bordered action groups and products in the cart. */
val BorderWidth = 1.dp

/** The smallest touch target Android accessibility guidance allows (48 × 48 dp). */
val MinTouchTarget = 48.dp

/** Shadow elevations; the app is flat apart from bars that float over scrolling content. */
object Elevation {
    /** The action bar pinned under a screen's content. */
    val ActionBar = 8.dp
}
