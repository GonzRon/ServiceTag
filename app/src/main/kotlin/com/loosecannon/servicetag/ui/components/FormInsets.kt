package com.loosecannon.servicetag.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable

/**
 * #121: the content insets of a screen that takes typed input — Material 3's default (the system bars and the
 * display cutout) and the keyboard. The root has consumed the bars, so on a screen this is the keyboard alone,
 * less what the root's bottom bar already covers.
 */
internal val formContentInsets: WindowInsets
    @Composable get() = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime)
