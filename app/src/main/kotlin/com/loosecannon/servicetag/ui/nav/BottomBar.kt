package com.loosecannon.servicetag.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.loosecannon.servicetag.ui.components.ServiceTagIcons

/**
 * Dashboard · Assets · Maintenance, and nothing else (1.2, spec §2.6 and the navigation ruling).
 *
 * Three destinations as of 1.2, up from the two 2B-2 left (D12 §16 correction). **Maintenance** is
 * the third because it is a place the owner goes looking: due work, the schedule list — including
 * the paused schedules the dashboard deliberately omits — the maintenance groups, and reminder
 * health. There is still no FAB and no overflow; Backup and Settings are reached from the
 * Dashboard, and Read / inspect tag from Settings, because a tag read is ambient dispatch and not
 * something the app asks anyone to open.
 */
@Composable
fun BottomBar(current: Route, onSelect: (Route) -> Unit) {
    NavigationBar {
        TopLevelRoutes.forEach { route ->
            NavigationBarItem(
                selected = route == current,
                onClick = { onSelect(route) },
                icon = { Icon(iconFor(route), contentDescription = null) },
                label = { Text(labelFor(route)) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun iconFor(route: Route): ImageVector = when (route) {
    Route.Assets -> Icons.AutoMirrored.Outlined.List
    Route.Maintenance -> ServiceTagIcons.Schedule
    else -> Icons.Outlined.Home
}

/** The labels, all three RATIFIED — "Maintenance" is spec §9.1's, quoted verbatim (D-24). */
private fun labelFor(route: Route): String = when (route) {
    Route.Assets -> "Assets"
    Route.Maintenance -> "Maintenance"
    else -> "Dashboard"
}
