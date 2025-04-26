package com.example.wearnote.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.MaterialTheme

@Composable
fun WearNoteTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        content = content
    )
}
