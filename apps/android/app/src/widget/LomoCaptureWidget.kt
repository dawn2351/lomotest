package com.lomo.app.widget

import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.lomo.app.R

class LomoCaptureWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*>? = null
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { GlanceTheme { CaptureWidgetContent(context) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { GlanceTheme { CaptureWidgetContent(context) } }
    }
}

class LomoCaptureWidgetReceiver : AppWidgetProvider() {
    private val dispatcher = LomoProjectionWidgetDispatcher(LomoCaptureWidgetReceiver::class.java, LomoCaptureWidget())

    override fun onReceive(context: Context, intent: Intent) {
        dispatcher.onReceive(this, context, intent) { super.onReceive(context, intent) }
    }
}

@Composable
private fun CaptureWidgetContent(context: Context) {
    WidgetShell(contentPadding = 6.dp) {
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = GlanceModifier
                    .defaultWeight()
                    .background(GlanceTheme.colors.primaryContainer)
                    .cornerRadius(24.dp)
                    .clickable(widgetLaunchAction(context, WidgetLaunchTarget.CreateMemo))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_plus),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
                    modifier = GlanceModifier.size(20.dp),
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = context.getString(R.string.widget_action_new_memo),
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 13.sp),
                )
            }
            Spacer(GlanceModifier.width(6.dp))
            WidgetActionIconButton(
                iconRes = R.drawable.ic_mic,
                contentDescription = context.getString(R.string.widget_action_record),
                onClick = widgetLaunchAction(context, WidgetLaunchTarget.StartRecording),
                sizeDp = 42.dp,
                iconSizeDp = 20.dp,
                cornerRadius = 21.dp,
            )
            Spacer(GlanceModifier.width(6.dp))
            WidgetActionIconButton(
                iconRes = R.drawable.ic_widget_camera,
                contentDescription = context.getString(R.string.widget_action_camera),
                onClick = widgetLaunchAction(context, WidgetLaunchTarget.TakePhoto),
                sizeDp = 42.dp,
                iconSizeDp = 20.dp,
                cornerRadius = 21.dp,
            )
        }
    }
}
