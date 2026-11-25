package com.frogweather.ui.navigation

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frogweather.ui.theme.FrogTheme

enum class AppTab(val label: String) {
    WEATHER("Wetter"),
    ANALYSIS("Analyse"),
    MAP("Karte"),
    PLANNER("Planer")
}

private fun AppTab.icon(): ImageVector = when (this) {
    AppTab.WEATHER -> Icons.Default.WbSunny
    AppTab.ANALYSIS -> Icons.Default.ShowChart
    AppTab.MAP -> Icons.Default.Map
    AppTab.PLANNER -> Icons.Default.EventNote
}

@Composable
fun FrogBottomNavigationBar(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = FrogTheme.palette
    BottomNavigation(
        modifier = modifier.navigationBarsPadding(),
        backgroundColor = palette.navBarBackground,
        contentColor = palette.textPrimary,
        elevation = 8.dp
    ) {
        AppTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            BottomNavigationItem(
                selected = selected,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        imageVector = tab.icon(),
                        contentDescription = tab.label
                    )
                },
                label = {
                    Text(
                        text = tab.label,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                },
                selectedContentColor = palette.accent,
                unselectedContentColor = palette.textSecondary
            )
        }
    }
}
