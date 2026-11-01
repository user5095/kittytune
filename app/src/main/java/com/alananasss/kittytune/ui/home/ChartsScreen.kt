package com.alananasss.kittytune.ui.home

    import android.content.Intent
    import androidx.compose.animation.animateColorAsState
    import androidx.compose.animation.core.tween
    import androidx.compose.foundation.BorderStroke
    import androidx.compose.foundation.background
    import androidx.compose.foundation.clickable
    import androidx.compose.foundation.layout.*
    import androidx.compose.foundation.lazy.LazyColumn
    import androidx.compose.foundation.lazy.LazyRow
    import androidx.compose.foundation.lazy.items
    import androidx.compose.foundation.lazy.itemsIndexed
    import androidx.compose.foundation.shape.CircleShape
    import androidx.compose.foundation.shape.RoundedCornerShape
    import androidx.compose.material.icons.Icons
    import androidx.compose.material.icons.automirrored.filled.ArrowBack
    import androidx.compose.material.icons.filled.MoreVert
    import androidx.compose.material.icons.filled.Person
    import androidx.compose.material.icons.filled.Radio
    import androidx.compose.material.icons.filled.Shuffle
    import androidx.compose.material.icons.outlined.Share
    import androidx.compose.material.icons.rounded.Check
    import androidx.compose.material.icons.rounded.KeyboardArrowDown
    import androidx.compose.material.icons.rounded.Verified
    import androidx.compose.material3.*
import com.alananasss.kittytune.ui.icons.Icon
    import androidx.compose.runtime.*
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.draw.clip
    import androidx.compose.ui.graphics.Brush
    import androidx.compose.ui.graphics.Color
    import androidx.compose.ui.graphics.vector.ImageVector
    import androidx.compose.ui.input.nestedscroll.nestedScroll
    import androidx.compose.ui.layout.ContentScale
    import androidx.compose.ui.platform.LocalContext
    import androidx.compose.ui.res.stringResource
    import androidx.compose.ui.text.font.FontWeight
    import androidx.compose.ui.text.style.TextOverflow
    import androidx.compose.ui.unit.dp
    import androidx.compose.ui.unit.sp
    import androidx.lifecycle.viewmodel.compose.viewModel
    import androidx.palette.graphics.Palette
    import coil.compose.AsyncImage
    import coil.imageLoader
    import coil.request.ImageRequest
    import com.alananasss.kittytune.R
    import com.alananasss.kittytune.data.ChartsData
    import com.alananasss.kittytune.domain.User
    import com.alananasss.kittytune.ui.common.SquareCardShimmer
    import com.alananasss.kittytune.ui.player.PlayerViewModel
    import com.alananasss.kittytune.ui.profile.ArtistAvatar
    import java.util.Locale

/**
 * The charts screen (desktop parity): what a country is listening to, one list, ranked as the
 * source ranks it. A chart is a queue — tapping a song plays the chart from there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartsScreen(
    onBackClick: () -> Unit,
    onPlaylistClick: (Long) -> Unit,
    onNavigate: (String) -> Unit,
    playerViewModel: PlayerViewModel,
    viewModel: ChartsViewModel = viewModel(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val visibleTracks = rememberFilteredTracks(viewModel.chartEntries.map { it.track })
    val chartEntries = remember(viewModel.chartEntries, visibleTracks) {
        val visibleIds = visibleTracks.map { it.id }.toSet()
        viewModel.chartEntries.filter { it.track.id in visibleIds }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.home_charts),
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp,
                    )
                },
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = onBackClick,
                        shapes = IconButtonDefaults.shapes(),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.btn_back))
                    }
                },
                actions = {
                    if (chartEntries.isNotEmpty()) {
                        FilledTonalIconButton(
                            onClick = hapticAction(HapticKind.UI) {
                                playerViewModel.prepareBulkAdd(chartEntries.map { it.track })
                            },
                            shapes = IconButtonDefaults.shapes(),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(R.string.menu_add_playlist)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 180.dp),
        ) {
            item {
                SongChart(
                    kind = viewModel.chartKind,
                    genre = viewModel.chartGenre,
                    genres = ChartRepository.chartGenres,
                    onKindChange = { viewModel.loadChart(it, viewModel.chartGenre) },
                    onGenreChange = { viewModel.loadChart(viewModel.chartKind, it) },
                    country = viewModel.chartCountry,
                    onCountryChange = { viewModel.selectChartCountry(it) },
                    isSwitching = viewModel.isChartLoading,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (viewModel.isChartLoading && chartEntries.isEmpty()) {
                items(8) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        ShimmerBox(Modifier.fillMaxSize())
                    }
                }
            } else if (chartEntries.isNotEmpty()) {
                itemsIndexed(chartEntries, key = { _, e -> "${e.rank}-${e.track.id}" }) { index, entry ->
                    val label = when (viewModel.chartKind) {
                        ChartRepository.ChartKind.COUNTRY -> viewModel.chartCountry.deezerName
                        ChartRepository.ChartKind.TOP -> stringResource(R.string.chart_kind_top)
                        ChartRepository.ChartKind.TRENDING -> stringResource(R.string.chart_kind_trending)
                    }
                    ChartTrackRow(
                        track = entry.track,
                        rank = entry.rank,
                        currentlyPlayingTrack = playerViewModel.uiCurrentTrack ?: playerViewModel.currentTrack,
                        onClick = hapticAction(HapticKind.UI) {
                            playerViewModel.playPlaylist(
                                tracks = chartEntries.map { it.track },
                                startIndex = index,
                                context = PlaybackContext(
                                    displayText = label,
                                    navigationId = "charts",
                                ),
                            )
                        },
                        onArtistClick = { track ->
                            track.user?.id?.let { artistId ->
                                onNavigate("profile:$artistId")
                            }
                        },
                        onOptionClick = hapticAction(HapticKind.UI) { playerViewModel.showTrackOptions(entry.track) },
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            } else {
                item {
                    Text(
                        stringResource(R.string.chart_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                    )
                }
            }
        }
    }
}
