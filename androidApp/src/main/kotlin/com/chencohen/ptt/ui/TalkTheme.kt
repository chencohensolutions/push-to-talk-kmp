package com.chencohen.ptt.ui

import androidx.compose.runtime.Composable
import com.chencohen.ptt.ui.style.LibraryTheme

/**
 * The app's colors: Journal's library palette, light or dark with the system (no wallpaper colors), so enrollment,
 * Chats and Settings look alike (docs/screens/style.md).
 */
@Composable
fun TalkTheme(content: @Composable () -> Unit) = LibraryTheme(content = content)
