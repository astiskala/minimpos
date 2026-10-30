package app.minimpos.app.ui.components

/** Which part of a shared navigation frame this screen supplies; no navigation or access policy is carried here. */
enum class ScreenHeader {
    /** Standalone screen, with its own app bar and mode banner. */
    FULL,

    /** List pane under a shared app bar; retains any list actions without repeating the title. */
    LIST_PANE,

    /** Detail pane under a shared app bar, with a local heading and any detail actions. */
    DETAIL_PANE,
}
