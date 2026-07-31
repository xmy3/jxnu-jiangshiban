package cn.jxnu.nvzhuanban.ui.screens.courseoffering

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.CourseOfferingForm
import cn.jxnu.nvzhuanban.data.model.CourseOfferingQuery
import cn.jxnu.nvzhuanban.data.model.CourseOfferingTable
import cn.jxnu.nvzhuanban.data.model.FormOption
import cn.jxnu.nvzhuanban.data.model.semesterStartDateOf
import cn.jxnu.nvzhuanban.ui.components.BackNavigationIcon
import cn.jxnu.nvzhuanban.ui.components.EmptyState
import cn.jxnu.nvzhuanban.ui.components.ErrorState
import cn.jxnu.nvzhuanban.ui.components.LoadingState
import cn.jxnu.nvzhuanban.ui.components.StateScaffold
import cn.jxnu.nvzhuanban.ui.theme.AppShape

/** 「不限」在教务网表单里的字面 value（四个下拉共用）。 */
private const val VALUE_UNRESTRICTED = "不限"

/**
 * 开课查询页：按 学期 / 学院 / 星期 / 节次 / 教室号 / 课程名 / 教师姓名 组合检索全校开课。
 *
 * 结果表列由教务网服务端决定（表头驱动渲染，见 [cn.jxnu.nvzhuanban.data.model.CourseOfferingTable]）。
 * 「查询」要求至少给一个**收敛性**条件（学院≠不限，或任一文本框非空）——全不限会把全校
 * 一学期的开课整表拉回来，响应大且对教务网不友好。
 *
 * 筛选表单区可折叠（折叠头常驻：标题 + 生效条件摘要 + 开合箭头，点击切换）：正常进入默认
 * 展开；带预填跳入默认收起——进屏即自动查询，先看结果，要改条件再展开。
 *
 * @param prefillTeacher 从课表「点教师」带入的教师姓名；非空时进屏预填并在表单就绪后自动查询。
 * @param prefillClassroom 从课表「点教室」带入的教室号；非空时同上。二者一般至多有一个非空。
 * @param prefillSemesterIsoDate 课表当前查看学期的开学日（ISO `yyyy-MM-dd`）；用于把本页学期
 *   下拉对齐到同一学期——本页默认学期是「最新」，学期末教务放出下学期选项后与课表会岔开一学期。
 *   匹配不上（历史学期不在本页下拉里 / 未传）retain 默认首项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseOfferingScreen(
    onBack: () -> Unit,
    prefillTeacher: String = "",
    prefillClassroom: String = "",
    prefillSemesterIsoDate: String = "",
    viewModel: CourseOfferingViewModel = viewModel(),
) {
    val formState by viewModel.formState.collectAsStateWithLifecycle()
    val resultState by viewModel.resultState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.course_offering_title)) },
                navigationIcon = { BackNavigationIcon(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        StateScaffold(
            state = formState,
            modifier = Modifier.padding(padding),
            onRetry = viewModel::loadForm,
        ) { form ->
            FormAndResults(
                form = form,
                resultState = resultState,
                prefillTeacher = prefillTeacher,
                prefillClassroom = prefillClassroom,
                prefillSemesterIsoDate = prefillSemesterIsoDate,
                onSearch = viewModel::search,
                onRetrySearch = viewModel::retrySearch,
            )
        }
    }
}

@Composable
private fun FormAndResults(
    form: CourseOfferingForm,
    resultState: CourseOfferingResultState,
    prefillTeacher: String,
    prefillClassroom: String,
    prefillSemesterIsoDate: String,
    onSearch: (CourseOfferingQuery) -> Unit,
    onRetrySearch: () -> Unit,
) {
    // 下拉选中值都存 value（不是 label）：value 是教务网回传口径，也是唯一稳定键。
    // null = 未选过 → 生效值取首项（学期首项即当前可查的最新学期）。
    // 学期初值：课表带入开学日时按日期对齐到本页下拉的同一学期（value 格式两边不同，只能比日期）；
    // 对不上（历史学期 / 未传）保持 null 走默认首项。
    var semesterValue by rememberSaveable {
        mutableStateOf(
            prefillSemesterIsoDate.takeIf { it.isNotBlank() }?.let { iso ->
                form.semesters.firstOrNull { semesterStartDateOf(it.value)?.toString() == iso }?.value
            },
        )
    }
    var collegeValue by rememberSaveable { mutableStateOf(VALUE_UNRESTRICTED) }
    var weekValue by rememberSaveable { mutableStateOf(VALUE_UNRESTRICTED) }
    var sectionValue by rememberSaveable { mutableStateOf(VALUE_UNRESTRICTED) }
    // 教室 / 教师文本框初值取预填（从课表带入）；用户后续编辑照常覆盖。
    var classroom by rememberSaveable { mutableStateOf(prefillClassroom) }
    var courseName by rememberSaveable { mutableStateOf("") }
    var teacherName by rememberSaveable { mutableStateOf(prefillTeacher) }
    // 筛选区折叠态：从课表带条件跳入（有预填）时默认收起——进屏即自动查询，用户要的是结果，
    // 展开的表单反而把结果顶出屏；「我的」正常进入默认展开。此后由用户点击开合，跨旋转保留。
    var filtersExpanded by rememberSaveable {
        mutableStateOf(prefillTeacher.isBlank() && prefillClassroom.isBlank())
    }
    val keyboard = LocalSoftwareKeyboardController.current

    val effectiveSemester = semesterValue ?: form.semesters.firstOrNull()?.value.orEmpty()
    // 至少一个收敛性条件：学院、或任一文本条件。星期/节次单独选仍是全校范围，不算数。
    val hasCriteria = collegeValue != VALUE_UNRESTRICTED ||
        classroom.isNotBlank() || courseName.isNotBlank() || teacherName.isNotBlank()

    val submit: () -> Unit = {
        if (hasCriteria) {
            keyboard?.hide()
            onSearch(
                CourseOfferingQuery(
                    semesterValue = effectiveSemester,
                    collegeValue = collegeValue,
                    weekValue = weekValue,
                    sectionValue = sectionValue,
                    classroom = classroom.trim(),
                    courseName = courseName.trim(),
                    teacherName = teacherName.trim(),
                ),
            )
        }
    }

    // 从课表带条件进来时自动查一次：表单此刻已就绪（StateScaffold Success 分支内），有预填才查。
    // 判据用 resultState 而非独立的 rememberSaveable 标志——标志会跨进程死亡持久化，而 VM 里的
    // resultState 不会，恢复后会出现「输入框有条件、结果区永远空白」的失配。用 resultState 判：
    // 旋转/返回重进（VM 存活，已是 Loading/Success/Error）→ 不重查；进程死亡恢复（VM 重建回
    // Initial）→ 恰好重新自动查，输入框与结果区保持一致。
    LaunchedEffect(Unit) {
        if (resultState is CourseOfferingResultState.Initial &&
            (prefillTeacher.isNotBlank() || prefillClassroom.isNotBlank())
        ) {
            submit()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = AppShape.card,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { filtersExpanded = !filtersExpanded }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "筛选条件",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!filtersExpanded) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = criteriaSummary(
                                    form = form,
                                    collegeValue = collegeValue,
                                    weekValue = weekValue,
                                    sectionValue = sectionValue,
                                    classroom = classroom,
                                    courseName = courseName,
                                    teacherName = teacherName,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Icon(
                        imageVector = if (filtersExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (filtersExpanded) "收起筛选" else "展开筛选",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                // 显式纵向展开/收起（同 TrainingPlanSummary）：默认转场是斜向 expandIn/shrinkOut，
                // 观感像从角落挤出来；限定竖直方向 + 淡入淡出才是「下拉抽屉」的正常观感。
                AnimatedVisibility(
                    visible = filtersExpanded,
                    enter = fadeIn(animationSpec = tween(200)) +
                        expandVertically(animationSpec = tween(250), expandFrom = Alignment.Top),
                    exit = fadeOut(animationSpec = tween(150)) +
                        shrinkVertically(animationSpec = tween(250), shrinkTowards = Alignment.Top),
                ) {
                    Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DropdownField(
                                label = "学期",
                                options = form.semesters,
                                selectedValue = effectiveSemester,
                                onSelect = { semesterValue = it.value },
                                modifier = Modifier.weight(1f),
                            )
                            DropdownField(
                                label = "学院",
                                options = form.colleges,
                                selectedValue = collegeValue,
                                onSelect = { collegeValue = it.value },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DropdownField(
                                label = "星期",
                                options = form.weeks,
                                selectedValue = weekValue,
                                onSelect = { weekValue = it.value },
                                modifier = Modifier.weight(1f),
                            )
                            DropdownField(
                                label = "节次",
                                options = form.sections,
                                selectedValue = sectionValue,
                                onSelect = { sectionValue = it.value },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = courseName,
                            onValueChange = { courseName = it },
                            label = { Text("课程名称（可模糊）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = classroom,
                                onValueChange = { classroom = it },
                                label = { Text("教室号") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = teacherName,
                                onValueChange = { teacherName = it },
                                label = { Text("教师姓名") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { submit() }),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = submit,
                            enabled = hasCriteria && resultState !is CourseOfferingResultState.Loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (resultState is CourseOfferingResultState.Loading) "查询中…" else "查询")
                        }
                        if (!hasCriteria) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "请先选择学院，或填写 课程 / 教室 / 教师 任一条件",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (val s = resultState) {
                is CourseOfferingResultState.Initial -> EmptyState(
                    message = "可查任意课程在哪上、某老师的课表、某教室的占用",
                    icon = Icons.Outlined.Search,
                )
                is CourseOfferingResultState.Loading -> LoadingState()
                is CourseOfferingResultState.Error -> ErrorState(
                    message = s.message,
                    onRetry = onRetrySearch,
                )
                is CourseOfferingResultState.Success -> ResultList(table = s.table)
            }
        }
    }
}

/**
 * 折叠头里的一行条件摘要：只列会影响查询的项——学期恒有不列，「不限」的下拉与空文本框跳过。
 * 下拉项用教务网 label 原文（学院名 / 星期一 / 第12节，自带语义无需前缀）；文本项加字段名前缀。
 * 顺序与展开表单一致。全空返回占位提示。
 */
private fun criteriaSummary(
    form: CourseOfferingForm,
    collegeValue: String,
    weekValue: String,
    sectionValue: String,
    classroom: String,
    courseName: String,
    teacherName: String,
): String {
    fun labelOf(options: List<FormOption>, value: String) =
        options.firstOrNull { it.value == value }?.label ?: value.trim()
    val parts = buildList {
        if (collegeValue != VALUE_UNRESTRICTED) add(labelOf(form.colleges, collegeValue))
        if (weekValue != VALUE_UNRESTRICTED) add(labelOf(form.weeks, weekValue))
        if (sectionValue != VALUE_UNRESTRICTED) add(labelOf(form.sections, sectionValue))
        if (courseName.isNotBlank()) add("课程 ${courseName.trim()}")
        if (classroom.isNotBlank()) add("教室 ${classroom.trim()}")
        if (teacherName.isNotBlank()) add("教师 ${teacherName.trim()}")
    }
    return if (parts.isEmpty()) "未设置条件" else parts.joinToString(" · ")
}

@Composable
private fun ResultList(table: CourseOfferingTable) {
    if (table.isEmpty) {
        EmptyState(
            message = table.message ?: "没有找到符合条件的开课记录",
            icon = Icons.Outlined.Search,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "__count__") {
            Text(
                text = "共 ${table.rows.size} 条",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        items(table.rows) { row ->
            ResultCard(columns = table.columns, row = row)
        }    }
}

/**
 * 结果卡片标题优先取「课程名」类列。实测 gvContent 真实列名是「课程名称标识」，
 * 故用**子串**匹配（不是精确相等）：列名里含「课程」且不是「课程讨论区」这种明显非课名列即可。
 * 一个都没命中时 titleIndex 为 -1，标题退回首格但正文会跳过该格（不重复渲染）。
 */
private val TITLE_HINTS = listOf("课程名称标识", "课程名称", "课程名", "课程")

/**
 * 整卡隐藏的列：「课程讨论区」（值是「进入讨论区」链接文本）——其目标 /WsktNew/
 * 已半死（展开即报系统错误），进 app 毫无价值；既不做标题也不渲染正文行。
 */
private fun isHiddenColumn(name: String): Boolean = name.contains("讨论区")


private fun pickTitleIndex(columns: List<String>, row: List<String>): Int {
    for (hint in TITLE_HINTS) {
        val idx = columns.indices.firstOrNull { i ->
            val c = columns[i]
            c.contains(hint) && !isHiddenColumn(c) && row.getOrNull(i)?.isNotBlank() == true
        }
        if (idx != null) return idx
    }
    return -1
}

@Composable
private fun ResultCard(columns: List<String>, row: List<String>) {
    val titleIndex = pickTitleIndex(columns, row)
    // titleIndex<0（无表头 / 没有课名列）时退回首格当标题；此时正文渲染要跳过第 0 格避免重复。
    val fallbackTitle = titleIndex < 0
    val title = if (titleIndex >= 0) row[titleIndex] else row.firstOrNull().orEmpty()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShape.listItem,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Spacer(Modifier.height(6.dp))
            if (columns.isEmpty()) {
                // 无表头（服务端未按 th 渲染）：逐格平铺。退回首格当标题时跳过第 0 格避免重复。
                val body = if (fallbackTitle) row.drop(1) else row
                body.filter { it.isNotBlank() }.forEach { cell ->
                    Text(text = cell, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                columns.forEachIndexed { i, col ->
                    // 跳过已用作标题的那一格、整卡隐藏列（课程讨论区），或回退时的第 0 格
                    if (i == titleIndex || (fallbackTitle && i == 0) || isHiddenColumn(col)) return@forEachIndexed
                    val value = row.getOrNull(i).orEmpty()
                    if (value.isBlank()) return@forEachIndexed
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        Text(
                            text = col,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(76.dp),
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * readOnly OutlinedTextField + 透明点击层 + [DropdownMenu] 的简易下拉。
 * 不用 ExposedDropdownMenuBox：其 menuAnchor API 在近几个 material3 版本里反复改名，
 * 这里的组合只依赖最稳定的原语。
 */
@Composable
private fun DropdownField(
    label: String,
    options: List<FormOption>,
    selectedValue: String?,
    onSelect: (FormOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label
        ?: options.firstOrNull()?.label.orEmpty()
    Box(modifier = modifier) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // readOnly TextField 自己会吞点击，盖一层透明可点区域接管展开
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
