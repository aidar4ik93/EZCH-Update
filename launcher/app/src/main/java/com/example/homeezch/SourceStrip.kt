package com.example.homeezch

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Serial reveal is cancellable: rapid toggles converge on the latest target. */
@Composable
internal fun SourceStrip(expanded: Boolean, sources: List<TvSourceEntry>, state: LazyListState,
    modifier: Modifier = Modifier, card: @Composable (TvSourceEntry) -> Unit) {
    val heavy = LocalRenderProfile.current.heavyEffects
    var count by remember { mutableIntStateOf(0) }
    LaunchedEffect(expanded, sources.size) {
        count = count.coerceAtMost(sources.size)
        val target = if (expanded) sources.size else 0
        while (count != target) {
            if (count < target) count++ else count--
            delay(if (heavy) 90 else 80)
        }
    }
    LazyRow(state = state, modifier = modifier.testTag("sources-list"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 14.dp)) {
        itemsIndexed(sources, key = { _, source -> source.id }) { index, source ->
            AnimatedVisibility(visible = index < count,
                enter = fadeIn(tween(if (heavy) 220 else 100)) + slideInHorizontally(tween(if (heavy) 220 else 100)) { it / 10 },
                exit = fadeOut(tween(if (heavy) 180 else 100)) + slideOutHorizontally(tween(if (heavy) 180 else 100)) { it / 10 }) { card(source) }
        }
    }
}
