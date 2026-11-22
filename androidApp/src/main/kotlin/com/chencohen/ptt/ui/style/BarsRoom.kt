package com.chencohen.ptt.ui.style

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/*
 * The system bars' room, kept while they are hidden (journal-client-kmp, where a full-screen viewer hides them): a
 * screen measured by the bars as they show moves when they show again. Measured by their room, nothing moves; the
 * talk mode screen (phase 5) will hide them the same way.
 */

/** The status bar's room, kept while it is hidden. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Modifier.statusBarRoom() = windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)

/** The same for the gesture bar at the bottom. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Modifier.navigationBarRoom() = windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)

/** Both bars' room as padding, for a list's content padding. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun barsRoom(): PaddingValues = WindowInsets.systemBarsIgnoringVisibility.asPaddingValues()

/** The gesture bar's room as insets, kept while it is hidden. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun navigationBarInsets(): WindowInsets = WindowInsets.navigationBarsIgnoringVisibility
