package com.chencohen.ptt

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.talk.TalkWidgetView
import com.chencohen.ptt.ui.TalkTheme
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryType
import kotlinx.coroutines.launch

/**
 * The home-screen talk widget (docs/screens/widget.md; 2026-11-22, hen): one conversation each. A widget can't be
 * held, so a tap starts talking there at once (the tap lets TalkReadyService open the microphone in the background)
 * and the next tap sends; ✕ cancels; 60 s sends by itself.
 */
class TalkWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { TalkWidgets.render(context, it, TalkWidgetView.Idle) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val prefs = TalkWidgets.prefs(context).edit()
        ids.forEach { prefs.remove(it.toString()) }
        prefs.apply()
    }
}

object TalkWidgets {
    fun prefs(context: Context) = context.getSharedPreferences("talk_widgets", Context.MODE_PRIVATE)

    /** The conversation and its name a widget talks to. */
    fun chatOf(context: Context, id: Int): Pair<String, String>? =
        prefs(context).getString(id.toString(), null)?.split('\n', limit = 2)?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    fun set(context: Context, id: Int, chat: String, name: String) {
        prefs(context).edit().putString(id.toString(), "$chat\n$name").apply()
    }

    private fun ids(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, TalkWidgetProvider::class.java))

    /** Every widget of [chat] shows [view]. */
    fun show(context: Context, chat: String, view: TalkWidgetView) {
        ids(context).filter { chatOf(context, it)?.first == chat }.forEach { render(context, it, view) }
    }

    fun render(context: Context, id: Int, view: TalkWidgetView) {
        val chat = chatOf(context, id)
        val views = RemoteViews(context.packageName, R.layout.talk_widget)
        if (chat == null) {
            views.setTextViewText(R.id.widget_name, context.getString(R.string.widget_choose))
            views.setTextViewText(R.id.widget_line, "")
            val configure = Intent(context, TalkWidgetConfigureActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, id, configure, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            AppWidgetManager.getInstance(context).updateAppWidget(id, views)
            return
        }
        val (key, name) = chat
        views.setTextViewText(R.id.widget_name, name)
        val (circle, line) = when (view) {
            TalkWidgetView.Idle -> R.drawable.widget_circle_pink to context.getString(R.string.widget_tap)
            is TalkWidgetView.Talking -> R.drawable.widget_circle_teal to context.getString(R.string.widget_talking, "%d:%02d".format(view.seconds / 60, view.seconds % 60))
            is TalkWidgetView.Incoming -> R.drawable.widget_circle_muted to Gendered.string(context, R.string.widget_incoming, view.user, view.user)
            is TalkWidgetView.Busy -> R.drawable.widget_circle_muted to Gendered.string(context, R.string.widget_busy, view.user, view.user)
        }
        views.setInt(R.id.widget_button, "setBackgroundResource", circle)
        views.setTextViewText(R.id.widget_line, line)
        views.setViewVisibility(R.id.widget_cancel, if (view is TalkWidgetView.Talking) View.VISIBLE else View.GONE)
        // The tap: talk, or send while talking (TalkReadyService toggles).
        val tap = Intent(context, TalkReadyService::class.java).setAction(TalkReadyService.ACTION_WIDGET).putExtra(TalkReadyService.EXTRA_CHAT, key)
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getForegroundService(context, id, tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        val cancel = Intent(context, TalkReadyService::class.java).setAction(TalkReadyService.ACTION_CANCEL_TALK)
        views.setOnClickPendingIntent(R.id.widget_cancel, PendingIntent.getService(context, id + 100_000, cancel, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        views.setContentDescription(R.id.widget_root, context.getString(R.string.widget_description_for, name))
        AppWidgetManager.getInstance(context).updateAppWidget(id, views)
    }
}

/** Choosing the conversation of a new talk widget: Quick talk chats first, then contacts and groups by name. */
class TalkWidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            TalkTheme {
                var chats by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
                LaunchedEffect(Unit) { chats = runCatching { talkWidgetChoices(this@TalkWidgetConfigureActivity) }.getOrDefault(emptyList()) }
                Column(Modifier.fillMaxSize().background(LibraryColors.Background).padding(top = 48.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.widget_pick_title), style = LibraryType.PageTitle, color = LibraryColors.OnBackground, modifier = Modifier.padding(20.dp))
                    val list = chats
                    when {
                        list == null -> {}
                        list.isEmpty() -> Text(stringResource(R.string.widget_pick_empty), color = LibraryColors.Muted, style = LibraryType.Body, modifier = Modifier.padding(20.dp))
                        else -> list.forEach { (chat, name) ->
                            Text(
                                name,
                                color = LibraryColors.OnBackground,
                                style = LibraryType.RowTitle,
                                modifier = Modifier.fillMaxWidth().clickable { pick(id, chat, name) }.padding(horizontal = 20.dp, vertical = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun pick(id: Int, chat: String, name: String) {
        TalkWidgets.set(this, id, chat, name)
        TalkWidgets.render(this, id, TalkWidgetView.Idle)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }
}
