package com.lomo.app.startup

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.lomo.app.R
import com.lomo.app.TrustedLaunchIntents
import com.lomo.app.WidgetDestination
import com.lomo.app.createOpenDestinationIntent

class AndroidDynamicShortcutPublisher(
    private val context: Context,
    private val trustedLaunchIntents: TrustedLaunchIntents,
) : DynamicShortcutPublisher {
    override fun publishExternalEntryShortcuts() {
        val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return
        val managedShortcutIds = setOf(NEW_MEMO_SHORTCUT_ID, START_RECORDING_SHORTCUT_ID, SEARCH_SHORTCUT_ID)
        val otherShortcuts = shortcutManager.dynamicShortcuts.filterNot { shortcut -> shortcut.id in managedShortcutIds }
        val availableSlots = (shortcutManager.maxShortcutCountPerActivity - otherShortcuts.size).coerceAtLeast(0)
        val shortcuts = listOf(::newMemoShortcut, ::recordingShortcut, ::searchShortcut)
        shortcutManager.dynamicShortcuts = otherShortcuts + shortcuts.take(availableSlots).map { build -> build() }
    }

    private fun newMemoShortcut(): ShortcutInfo = ShortcutInfo
        .Builder(context, NEW_MEMO_SHORTCUT_ID)
        .setShortLabel(context.getString(R.string.shortcut_new_memo_short_label))
        .setLongLabel(context.getString(R.string.shortcut_new_memo_long_label))
        .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_new_memo))
        .setIntent(trustedLaunchIntents.trustedShortcutCreateMemoIntent())
        .build()

    private fun recordingShortcut(): ShortcutInfo = ShortcutInfo
        .Builder(context, START_RECORDING_SHORTCUT_ID)
        .setShortLabel(context.getString(R.string.shortcut_start_recording_short_label))
        .setLongLabel(context.getString(R.string.shortcut_start_recording_long_label))
        .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_recording))
        .setIntent(trustedLaunchIntents.trustedShortcutStartRecordingIntent())
        .build()

    private fun searchShortcut(): ShortcutInfo = ShortcutInfo
        .Builder(context, SEARCH_SHORTCUT_ID)
        .setShortLabel(context.getString(R.string.shortcut_search_label))
        .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_search))
        .setIntent(createOpenDestinationIntent(context, WidgetDestination.SEARCH))
        .build()

    private companion object {
        const val NEW_MEMO_SHORTCUT_ID = "new_memo"
        const val START_RECORDING_SHORTCUT_ID = "start_recording"
        const val SEARCH_SHORTCUT_ID = "search"
    }
}
