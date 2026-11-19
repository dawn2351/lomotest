package com.lomo.app.feature.statistics

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.FileProvider
import com.lomo.app.R
import com.lomo.app.feature.common.PendingUiEvent
import com.lomo.app.util.requireSuccessfulPngEncode
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.ui.theme.AppSpacing
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

private const val STATS_SCREENSHOT_PNG_QUALITY = 100

@Composable
internal fun StatisticsShareEffects(
    context: Context,
    shareImageEvents: List<PendingUiEvent<String>>,
    shareErrorMessage: String?,
    snackbarHostState: SnackbarHostState,
    onShareEventConsumed: (Long) -> Unit,
    onShareFailed: (Throwable) -> Unit,
    onShareErrorConsumed: () -> Unit,
) {
    LaunchedEffect(shareImageEvents) {
        val event = shareImageEvents.lastOrNull() ?: return@LaunchedEffect
        runCatching {
            shareStatisticsImageFile(context = context, filePath = event.payload)
        }.onFailure(onShareFailed)
        onShareEventConsumed(event.id)
    }
    LaunchedEffect(shareErrorMessage) {
        shareErrorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onShareErrorConsumed()
        }
    }
}

interface StatisticsPngSource {
    suspend fun writeTo(output: OutputStream)

    fun close()
}

internal suspend fun captureStatisticsPngSource(
    contentLayer: GraphicsLayer,
    graphicsContext: GraphicsContext,
    density: Density,
    layoutDirection: LayoutDirection,
    backgroundColor: Color,
): StatisticsPngSource {
    val sidePadding = with(density) { AppSpacing.Large.roundToPx() }
    val contentSize = contentLayer.size
    check(contentSize.width > 0 && contentSize.height > 0) { "Statistics content has not been drawn" }
    val exportLayer = graphicsContext.createGraphicsLayer()
    try {
        exportLayer.record(
            density = density,
            layoutDirection = layoutDirection,
            size = IntSize(contentSize.width + 2 * sidePadding, contentSize.height),
        ) {
            drawRect(backgroundColor)
            translate(left = sidePadding.toFloat()) {
                drawLayer(contentLayer)
            }
        }
        return BitmapStatisticsPngSource(exportLayer.toImageBitmap().asAndroidBitmap())
    } finally {
        graphicsContext.releaseGraphicsLayer(exportLayer)
    }
}

private class BitmapStatisticsPngSource(
    private val bitmap: android.graphics.Bitmap,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) : StatisticsPngSource {
    override suspend fun writeTo(output: OutputStream) {
        withContext(dispatcherProvider.default) {
            requireSuccessfulPngEncode(
                bitmap.compress(Bitmap.CompressFormat.PNG, STATS_SCREENSHOT_PNG_QUALITY, output),
            )
        }
    }

    override fun close() {
        bitmap.recycle()
    }
}

private fun shareStatisticsImageFile(context: Context, filePath: String) {
    val imageUri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(filePath),
        )
    val sendIntent =
        Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, "statistics_image", imageUri)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    context.startActivity(
        Intent.createChooser(
            sendIntent,
            context.getString(R.string.stats_share_sheet_title),
        ),
    )
}
