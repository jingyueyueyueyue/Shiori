package org.galplayer.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.galplayer.launcher.CoverSearchState
import org.galplayer.launcher.LauncherViewModel
import org.galplayer.launcher.cover.CoverCandidate

@Composable
fun CoverSearchDialog(state: CoverSearchState, adultProtection: Boolean, viewModel: LauncherViewModel) {
    var query by remember(state.gameId) { mutableStateOf(state.query) }
    AlertDialog(
        onDismissRequest = viewModel::closeCoverSearch,
        modifier = Modifier.heightIn(max = 720.dp),
        title = { Text("在线封面搜索") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, label = { Text("游戏名或 RJ/VJ 编号") })
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = { viewModel.searchCovers(query) }, enabled = query.isNotBlank() && !state.loading) { Text("搜索") }
                }
                Spacer(Modifier.height(10.dp))
                if (state.loading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().height(480.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.results, key = { it.url }) { candidate ->
                        CoverCandidateCard(candidate, adultProtection, onSelect = { viewModel.selectCover(candidate) })
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = viewModel::closeCoverSearch) { Text("关闭") } },
    )
}

@Composable
private fun CoverCandidateCard(candidate: CoverCandidate, adultProtection: Boolean, onSelect: () -> Unit) {
    val image = rememberArtwork(candidate.thumbnailUrl, 500)
    Column(Modifier.clickable(onClick = onSelect)) {
        Surface(Modifier.fillMaxWidth().aspectRatio(0.75f), shape = MaterialTheme.shapes.medium) {
            Box {
                if (image != null) {
                    Image(
                        image,
                        candidate.title,
                        Modifier.fillMaxSize().then(if (adultProtection && candidate.sensitive) Modifier.blur(22.dp) else Modifier),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(candidate.source.label) }
                }
                if (adultProtection && candidate.sensitive) {
                    Surface(Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)) {
                        Text("敏感内容", Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Text(candidate.source.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(candidate.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
}
