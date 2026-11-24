package com.chencohen.ptt.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chencohen.ptt.R
import com.chencohen.ptt.messaging.Gender
import com.chencohen.ptt.ui.style.LibraryColors
import com.chencohen.ptt.ui.style.LibraryIcons
import com.chencohen.ptt.ui.style.LibraryShapes
import com.chencohen.ptt.ui.style.LibraryType

/**
 * "How should the app refer to you?" (2026-11-23, hen): Male, Female, or not said (the slash forms; the default). In
 * the enrollment wizard, Settings › Profile, and once as a card for those who never chose.
 */
@Composable
internal fun GenderChoice(gender: Int, choose: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            Gender.MALE to R.string.profile_gender_male,
            Gender.FEMALE to R.string.profile_gender_female,
            Gender.NOT_SET to R.string.profile_gender_not_set,
        ).forEach { (g, label) ->
            val on = g == gender
            Text(
                stringResource(label),
                color = if (on) Color.White else LibraryColors.OnBackground,
                style = LibraryType.Small,
                modifier = Modifier.background(if (on) com.chencohen.ptt.ui.style.Pink else LibraryColors.Background, CircleShape)
                    .semantics { selected = on }
                    .clickable(role = Role.RadioButton) { choose(g) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** Once, for those who never chose (after an update): the same three, and ✕. */
@Composable
internal fun GenderCard(choose: (Int) -> Unit, dismiss: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().background(LibraryColors.Raised, LibraryShapes.Card).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.profile_gender_title), color = LibraryColors.OnBackground, style = LibraryType.RowTitle, modifier = Modifier.weight(1f))
            val close = stringResource(R.string.quick_reply_close)
            Box(Modifier.size(36.dp).clickable(role = Role.Button, onClickLabel = close, onClick = dismiss), contentAlignment = Alignment.Center) {
                Icon(LibraryIcons.Close, close, tint = LibraryColors.Muted)
            }
        }
        Text(stringResource(R.string.profile_gender_body), color = LibraryColors.Muted, style = LibraryType.Caption, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        GenderChoice(Gender.NOT_SET, choose)
    }
}

/** Whether this phone asked already (the wizard, Settings or the card). */
internal object GenderAsked {
    private fun prefs(context: Context) = context.getSharedPreferences("ptt_prefs", Context.MODE_PRIVATE)
    fun asked(context: Context): Boolean = prefs(context).getString("gender_asked", null) == "true"
    fun mark(context: Context) = prefs(context).edit().putString("gender_asked", "true").apply()

    /** Before the app's messaging runs (the wizard): straight into its settings, shared once it starts. */
    fun saveEarly(context: Context, gender: Int) {
        prefs(context).edit().putString("my_gender", gender.toString()).putString("gender_asked", "true").apply()
        com.chencohen.ptt.Gendered.applyMine(context, gender)
    }
}
