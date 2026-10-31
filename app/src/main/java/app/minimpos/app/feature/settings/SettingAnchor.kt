package app.minimpos.app.feature.settings

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned

/** Brings the exact control into view once after layout, not again while the operator scrolls or types. */
@Composable
internal fun Modifier.settingAnchor(
    tag: String,
    target: String?,
): Modifier {
    if (tag != target) return this
    val requester = remember { BringIntoViewRequester() }
    var height by remember { mutableIntStateOf(0) }
    var shown by rememberSaveable(tag) { mutableStateOf(false) }
    LaunchedEffect(height) {
        if (!shown && height > 0) {
            requester.bringIntoView()
            shown = true
        }
    }
    return bringIntoViewRequester(requester).onGloballyPositioned { height = it.size.height }
}
