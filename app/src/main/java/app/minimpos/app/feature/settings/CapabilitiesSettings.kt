package app.minimpos.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.NotInterested
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import app.minimpos.app.R
import app.minimpos.app.feature.GatedButton
import app.minimpos.app.feature.text
import app.minimpos.app.terminal.AdyenCapabilitiesState
import app.minimpos.app.ui.components.Notice
import app.minimpos.app.ui.components.NoticeKind
import app.minimpos.app.ui.components.SectionHeader
import app.minimpos.app.ui.components.SetupRequired
import app.minimpos.app.ui.components.StatusKind
import app.minimpos.app.ui.components.TertiaryButton
import app.minimpos.app.ui.components.color
import app.minimpos.app.ui.components.currentSetupGate
import app.minimpos.app.ui.components.icon
import app.minimpos.app.ui.theme.IconSize
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.app.ui.theme.MinTouchTarget
import app.minimpos.app.ui.theme.Spacing

/** One expanded task-grouped hub; unsupported entries remain available in one disclosure at the bottom. */
@Composable
internal fun ColumnScope.CapabilitiesSection(
    capabilities: AdyenCapabilitiesState,
    items: List<CapabilityItem>,
    refreshingBlocked: Boolean,
    onRefresh: () -> Unit,
    onOpen: (CapabilityFeature) -> Unit,
) {
    SettingIntro(stringResource(R.string.capabilities_intro))
    if (currentSetupGate().pending) {
        SetupRequired()
        return
    }
    CapabilityRefresh(capabilities, refreshingBlocked, onRefresh)
    CapabilityGroup.entries.forEach { group ->
        val supported = items.filter { it.feature.group == group && it.status != CapabilityStatus.UNSUPPORTED }
        if (supported.isNotEmpty()) {
            SectionHeader(
                stringResource(
                    when (group) {
                        CapabilityGroup.PAYMENTS -> R.string.settings_payments
                        CapabilityGroup.SHOPPER -> R.string.capability_group_shopper
                        CapabilityGroup.RECEIPTS -> R.string.capability_group_receipts
                        CapabilityGroup.TERMINAL -> R.string.capability_group_terminal
                    },
                ),
            )
            supported.forEach { CapabilityRow(it) { onOpen(it.feature) } }
        }
    }
    val unsupported = items.filter { it.status == CapabilityStatus.UNSUPPORTED }
    if (unsupported.isNotEmpty()) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        SettingActions {
            TertiaryButton(
                stringResource(
                    if (expanded) R.string.capability_hide_unsupported else R.string.capability_show_unsupported,
                    unsupported.size,
                ),
                { expanded = !expanded },
                Modifier.testTag("unsupportedCapabilities"),
                icon = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            )
        }
        if (expanded) {
            SettingNote(stringResource(R.string.capability_unsupported_note))
            unsupported.forEach { CapabilityRow(it) { onOpen(it.feature) } }
        }
    }
}

/** Explicit refresh is also available on a feature detail when its initial read failed. */
@Composable
internal fun ColumnScope.CapabilityRefresh(
    reading: AdyenCapabilitiesState,
    blocked: Boolean,
    onRefresh: () -> Unit,
) {
    reading.failure?.let { Notice(it.text(), kind = NoticeKind.WARNING, inset = true) }
    val refreshable = reading.capabilities.discoversTerminals || reading.simulated
    // The refresh button already explains a setup problem as its blocked reason.
    if (!refreshable) reading.problem?.let { Notice(it.text(), kind = NoticeKind.WARNING, inset = true) }
    if (refreshable) {
        SettingActions {
            GatedButton(
                stringResource(R.string.capabilities_refresh),
                onRefresh,
                blocked = reading.refreshBlocked,
                loading = reading.checking,
                enabled = !blocked,
                icon = Icons.Default.Refresh,
                modifier = Modifier.testTag("refreshCapabilities"),
            )
        }
    }
}

@Composable
private fun CapabilityRow(
    item: CapabilityItem,
    onOpen: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(horizontal = LocalDimens.current.rowHorizontalPadding, vertical = LocalDimens.current.rowPadding)
                .testTag(item.feature.tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.XSmall)) {
                Text(stringResource(item.feature.titleRes), style = MaterialTheme.typography.bodyLarge)
                CapabilityStatusLine(item)
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(Modifier.padding(start = LocalDimens.current.rowHorizontalPadding))
    }
}

/** Status is below the title, with an outcome glyph, or a neutral one for Off and Unsupported, which are not outcomes. */
@Composable
internal fun CapabilityStatusLine(item: CapabilityItem) {
    val status = item.status
    val kind =
        when (status) {
            CapabilityStatus.READY -> StatusKind.SUCCESS
            CapabilityStatus.NEEDS_SETUP, CapabilityStatus.UNVERIFIED -> StatusKind.WARNING
            CapabilityStatus.OFF, CapabilityStatus.UNSUPPORTED -> null
        }
    val color = kind?.color() ?: MaterialTheme.colorScheme.onSurfaceVariant
    val label =
        when (status) {
            CapabilityStatus.READY -> {
                if (item.feature in setOf(CapabilityFeature.DCC, CapabilityFeature.SURCHARGE, CapabilityFeature.OFFLINE)) {
                    R.string.capability_status_configured
                } else {
                    R.string.capability_status_available
                }
            }

            CapabilityStatus.OFF -> {
                R.string.capability_status_off
            }

            CapabilityStatus.NEEDS_SETUP -> {
                R.string.capability_status_needs_config
            }

            CapabilityStatus.UNVERIFIED -> {
                R.string.capability_status_unverified
            }

            CapabilityStatus.UNSUPPORTED -> {
                R.string.capability_status_unsupported
            }
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.XSmall)) {
        Icon(
            kind?.icon() ?: if (status == CapabilityStatus.OFF) Icons.Default.RadioButtonUnchecked else Icons.Default.NotInterested,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(IconSize.Small),
        )
        Text(stringResource(label), color = color, style = MaterialTheme.typography.bodyMedium)
    }
}
