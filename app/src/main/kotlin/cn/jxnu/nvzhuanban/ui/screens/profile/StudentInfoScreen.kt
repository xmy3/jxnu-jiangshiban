package cn.jxnu.nvzhuanban.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jxnu.nvzhuanban.data.model.StudentBasicInfo
import cn.jxnu.nvzhuanban.ui.components.BackNavigationIcon
import cn.jxnu.nvzhuanban.ui.components.LabeledInfoCard
import cn.jxnu.nvzhuanban.ui.components.RefreshIconButton
import cn.jxnu.nvzhuanban.ui.components.StateScaffold
import cn.jxnu.nvzhuanban.ui.components.rememberTransientErrorSnackbar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentInfoScreen(
    onBack: () -> Unit = {},
    viewModel: StudentInfoViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    Scaffold(
        snackbarHost = { SnackbarHost(rememberTransientErrorSnackbar(viewModel.refreshFailed)) },
        topBar = {
            TopAppBar(
                title = { Text("基本信息") },
                navigationIcon = { BackNavigationIcon(onBack) },
                actions = { RefreshIconButton(isRefreshing = isRefreshing, onClick = viewModel::refresh) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            StateScaffold(state = state, onRetry = viewModel::load) { info ->
                StudentInfoContent(info)
            }
        }
    }
}

@Composable
private fun StudentInfoContent(info: StudentBasicInfo) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LabeledInfoCard(
            rows = buildList {
                add("学号" to info.studentId)
                add("姓名" to info.name)
                if (info.className.isNotBlank()) add("班级" to info.className)
                if (info.gender.isNotBlank()) add("性别" to info.gender)
                if (info.ethnicity.isNotBlank()) add("民族" to info.ethnicity)
                if (info.birthDate.isNotBlank()) add("出生日期" to info.birthDate)
                if (info.examId.isNotBlank()) add("考生号" to info.examId)
            },
        )
        Text(
            text = "以上为教育部学信网登记信息，仅本地展示、不上传。如有错误请到教务处更正。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
