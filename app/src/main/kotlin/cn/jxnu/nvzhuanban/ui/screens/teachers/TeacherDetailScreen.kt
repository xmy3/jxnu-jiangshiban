package cn.jxnu.nvzhuanban.ui.screens.teachers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.TeacherInfo
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.ui.components.BackNavigationIcon
import cn.jxnu.nvzhuanban.ui.components.LabeledInfoCard
import cn.jxnu.nvzhuanban.ui.components.PersonDetailHeader
import cn.jxnu.nvzhuanban.ui.components.StateScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeacherDetailScreen(
    userNum: String,
    name: String,
    onBack: () -> Unit,
    onOpenSchedule: () -> Unit,
    viewModel: TeacherDetailViewModel = viewModel(factory = TeacherDetailViewModel.factory(userNum)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name.ifBlank { stringResource(R.string.teacher_detail_title_fallback) }) },
                navigationIcon = { BackNavigationIcon(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        StateScaffold(
            state = state,
            modifier = Modifier.padding(padding),
            onRetry = viewModel::load,
            loading = { m -> cn.jxnu.nvzhuanban.ui.components.PersonDetailSkeleton(modifier = m) },
        ) { info ->
            TeacherDetailBody(
                userNum = userNum,
                fallbackName = name,
                info = info,
                onOpenSchedule = onOpenSchedule,
            )
        }
    }
}

@Composable
private fun TeacherDetailBody(
    userNum: String,
    fallbackName: String,
    info: TeacherInfo,
    onOpenSchedule: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            PersonDetailHeader(
                photoUrl = JxnuUrls.teacherPhotoUrl(userNum),
                name = info.name.ifBlank { fallbackName },
                subtitle = listOfNotNull(
                    info.gender.takeIf { it.isNotBlank() },
                    info.title.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
            )
        }
        item {
            LabeledInfoCard(
                rows = listOfNotNull(
                    info.title.takeIf { it.isNotBlank() }?.let { "职称" to it },
                    info.email.takeIf { it.isNotBlank() }?.let { "Email" to it },
                ),
            )
        }
        if (info.intro.isNotBlank()) {
            item { IntroCard(intro = info.intro) }
        }
        item {
            Button(
                onClick = onOpenSchedule,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.teacher_detail_open_schedule))
            }
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun IntroCard(intro: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.teacher_detail_intro_label),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = intro,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
