package com.lomo.app.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.lomo.domain.model.SecuritySessionState
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.SecuritySessionPolicy
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.time.LocalDate

private const val ACTION_REFRESH_REVIEW = "com.lomo.app.widget.REFRESH_REVIEW"
private const val REVIEW_REFRESH_TIMEOUT_MILLIS = 8_000L

internal fun reviewWidgetRefreshIntent(context: Context, appWidgetId: Int? = null): Intent =
    Intent(context, WidgetReviewRefreshReceiver::class.java).apply {
        action = ACTION_REFRESH_REVIEW
        if (appWidgetId != null) {
            data = "lomo://widget-review/$appWidgetId".toUri()
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
    }

/** The main process owns fresh projections; the widget process only reads their published file. */
class WidgetReviewRefreshReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val dispatchers: DispatcherProvider by inject()
    private val engine: EngineReadinessRepository by inject()
    private val security: SecuritySessionPolicy by inject()
    private val projections: WidgetProjectionBinder by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REFRESH_REVIEW) return
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val reviewIds = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, LomoReviewWidgetReceiver::class.java))
        if (reviewIds.isEmpty()) return
        val manual = appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        if (manual && appWidgetId !in reviewIds) return
        val pending = goAsync() ?: return
        // behavior-contract: unmanaged-scope-ok: bounded broadcast work owns PendingResult until finish
        CoroutineScope(dispatchers.io).launch {
            try {
                withTimeout(REVIEW_REFRESH_TIMEOUT_MILLIS) {
                    refresh(context.applicationContext, appWidgetId.takeIf { manual })
                }
            } catch (failure: TimeoutCancellationException) {
                Timber.w(failure, "Widget review refresh timed out")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Timber.w(failure, "Widget review refresh failed")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun refresh(context: Context, appWidgetId: Int?) {
        if (security.current() != SecuritySessionState.LockOff) return
        val before = readWidgetRenderSnapshot(context)
        val cycled = appWidgetId != null && cycleReview(context, appWidgetId, before)
        val today = LocalDate.now().toEpochDay()
        if (before.stats?.asOfEpochDay == today && (appWidgetId == null || cycled)) return

        engine.requestEngineStart()
        if (engine.mount.value.admitsProjectionReads) projections.refreshNow()
        if (appWidgetId != null && !cycled && security.current() == SecuritySessionState.LockOff) {
            cycleReview(context, appWidgetId, readWidgetRenderSnapshot(context))
        }
    }

    private suspend fun cycleReview(context: Context, appWidgetId: Int, snapshot: WidgetRenderSnapshot): Boolean {
        if (snapshot.availability != WidgetSnapshotAvailability.READY || snapshot.reviews.size < 2) return false
        WidgetReviewCursorStore(context.filesDir.resolve(WIDGET_REVIEW_CURSOR_FILE_NAME))
            .advanceCursor(appWidgetId.toString(), snapshot.reviews.size)
        WidgetUpdater.updateReviewWidget(context, appWidgetId)
        return true
    }
}
