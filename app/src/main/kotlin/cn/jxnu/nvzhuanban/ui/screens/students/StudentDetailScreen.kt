package cn.jxnu.nvzhuanban.ui.screens.students

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.StudentInfo
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.ui.components.BackNavigationIcon
import cn.jxnu.nvzhuanban.ui.components.LabeledInfoCard
import cn.jxnu.nvzhuanban.ui.components.PersonDetailHeader
import cn.jxnu.nvzhuanban.ui.components.StateScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentDetailScreen(
    userNum: String,
    name: String,
    department: String,
    onBack: () -> Unit,
    onOpenSchedule: () -> Unit,
    viewModel: StudentDetailViewModel = viewModel(factory = StudentDetailViewModel.factory(userNum)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name.ifBlank { stringResource(R.string.student_detail_title_fallback) }) },
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
            StudentDetailBody(
                userNum = userNum,
                fallbackName = name,
                department = department,
                info = info,
                onOpenSchedule = onOpenSchedule,
            )
        }
    }
}

@Composable
private fun StudentDetailBody(
    userNum: String,
    fallbackName: String,
    department: String,
    info: StudentInfo,
    onOpenSchedule: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            PersonDetailHeader(
                photoUrl = JxnuUrls.studentPhotoUrl(userNum),
                name = info.name.ifBlank { fallbackName },
                subtitle = listOfNotNull(
                    info.gender.takeIf { it.isNotBlank() },
                    department.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
            )
        }
        item {
            LabeledInfoCard(
                rows = listOfNotNull(
                    info.studentId.takeIf { it.isNotBlank() }?.let { "学号" to it },
                    info.className.takeIf { it.isNotBlank() }?.let { "班级" to it },
                    department.takeIf { it.isNotBlank() }?.let { "所在单位" to it },
                ),
            )
        }
        item {
            Button(
                onClick = onOpenSchedule,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.student_detail_open_schedule))
            }
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}
