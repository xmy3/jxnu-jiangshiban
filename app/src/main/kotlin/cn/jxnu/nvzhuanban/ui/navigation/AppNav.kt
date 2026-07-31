package cn.jxnu.nvzhuanban.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cn.jxnu.nvzhuanban.NvzhuanbanApp
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.AuthState
import cn.jxnu.nvzhuanban.data.network.SessionEvents
import cn.jxnu.nvzhuanban.data.repository.AuthRepository
import cn.jxnu.nvzhuanban.data.storage.AnnouncementUnreadState
import cn.jxnu.nvzhuanban.ui.screens.announcement.AnnouncementDetailScreen
import cn.jxnu.nvzhuanban.ui.screens.announcement.AnnouncementScreen
import cn.jxnu.nvzhuanban.ui.screens.calendar.CalendarDocumentScreen
import cn.jxnu.nvzhuanban.ui.screens.calendar.CalendarScreen
import cn.jxnu.nvzhuanban.ui.screens.classroom.ClassroomScreen
import cn.jxnu.nvzhuanban.ui.screens.courseoffering.CourseOfferingScreen
import cn.jxnu.nvzhuanban.ui.screens.exams.ExamsScreen
import cn.jxnu.nvzhuanban.ui.screens.grades.GradesScreen
import cn.jxnu.nvzhuanban.ui.screens.login.LoginScreen
import cn.jxnu.nvzhuanban.ui.screens.peoplesearch.PeopleSearchScreen
import cn.jxnu.nvzhuanban.ui.screens.peoplesearch.PersonResult
import cn.jxnu.nvzhuanban.ui.screens.profile.ProfileScreen
import cn.jxnu.nvzhuanban.ui.screens.profile.StudentInfoScreen
import cn.jxnu.nvzhuanban.ui.screens.schedule.ScheduleScreen
import cn.jxnu.nvzhuanban.ui.screens.students.StudentDetailScreen
import cn.jxnu.nvzhuanban.ui.screens.teachers.TeacherDetailScreen
import cn.jxnu.nvzhuanban.ui.screens.trainingplan.TrainingPlanScreen
import cn.jxnu.nvzhuanban.ui.screens.userschedule.UserScheduleScreen

object Routes {
    const val LOGIN = "login"
    const val SCHEDULE = "schedule"
    const val GRADES = "grades"
    const val STUDENT_INFO = "student_info"
    const val EXAMS = "exams"
    const val ANNOUNCEMENT = "announcement"
    const val CLASSROOM = "classroom"
    const val TRAINING_PLAN = "training_plan"
    const val CALENDAR = "calendar"

    /**
     * 校历 PDF 的 app 内查看页。url 是完整 https 文件地址（含 `:/`），title 用作顶栏标题，
     * 两段都必须 [android.net.Uri.encode]（同师生详情路由的 path 参数纪律）。
     */
    const val CALENDAR_DOCUMENT = "calendar_document/{url}/{title}"
    fun calendarDocument(url: String, title: String) =
        "calendar_document/${android.net.Uri.encode(url)}/${android.net.Uri.encode(title)}"
    /**
     * 开课查询（Public_Kkap）：按学期/学院/教室/课程/教师检索全校开课。
     *
     * 用 `{field}/{value}/{semester}` 三段 **path 参数**（而非 query 参数）承载从课表带入的
     * 预填条件——path 参数的 `Uri.encode` + 自动解码行为在本项目已被师生详情路由验证过
     * （中文名往返正确），query 参数的解码行为在 Navigation 里不一致（值常不被自动解码，
     * 中文教师名会往返失真）。
     *
     * [field] 取 [FIELD_TEACHER] / [FIELD_CLASSROOM] / [FIELD_NONE]；[value] 是原始教师名或
     * 教室号；[semester] 是课表当前查看学期的**开学日 ISO 串**（如 `2026-03-01`），开课查询端
     * 按开学日与自家学期下拉对齐（两边 value 格式不同：`2026\3\1` vs `2026/3/1`，不能直接比）。
     * `-` 表示段缺省。课表点击本就是「教师」「教室」二选一。
     */
    const val COURSE_OFFERING = "course_offering/{field}/{value}/{semester}"
    fun courseOffering(
        teacher: String? = null,
        classroom: String? = null,
        semesterIsoDate: String? = null,
    ): String {
        val (field, value) = when {
            !teacher.isNullOrBlank() -> FIELD_TEACHER to teacher
            !classroom.isNullOrBlank() -> FIELD_CLASSROOM to classroom
            else -> FIELD_NONE to "-"
        }
        val semester = semesterIsoDate?.takeIf { it.isNotBlank() } ?: "-"
        return "course_offering/$field/${android.net.Uri.encode(value)}/${android.net.Uri.encode(semester)}"
    }

    /** [COURSE_OFFERING] 的 field 段取值：无预填 / 预填教师 / 预填教室。 */
    const val FIELD_NONE = "none"
    const val FIELD_TEACHER = "teacher"
    const val FIELD_CLASSROOM = "classroom"
    /** 教工 + 学生合并后的统一查询入口；进入后由顶部 chip 切换。 */
    const val PEOPLE_SEARCH = "people_search"
    const val PROFILE = "profile"

    /** 带 articleId 参数的详情页路由；用 [announcementDetail] 构造实例。 */
    const val ANNOUNCEMENT_DETAIL = "announcement_detail/{articleId}"
    // id 现实中恒为纯数字，encode 是与其它路由构造器统一的防御（未来 id 混入 `/` 也不破路由）。
    fun announcementDetail(articleId: String) = "announcement_detail/${android.net.Uri.encode(articleId)}"

    /** 教工详情 / 教工课表：userNum 是教号 base64，可能含 `+ /`，需要 [android.net.Uri.encode]。 */
    const val TEACHER_DETAIL = "teacher_detail/{userNum}/{name}"
    fun teacherDetail(userNum: String, name: String) =
        "teacher_detail/${android.net.Uri.encode(userNum)}/${android.net.Uri.encode(name)}"

    const val TEACHER_SCHEDULE = "teacher_schedule/{userNum}/{name}"
    fun teacherSchedule(userNum: String, name: String) =
        "teacher_schedule/${android.net.Uri.encode(userNum)}/${android.net.Uri.encode(name)}"

    /**
     * 学生详情 / 学生课表：userNum 是学号 base64（教务网常带 `==` 结尾），
     * 多出一个 `department` 路径段，把检索列表里抓到的「所在单位」带过来——详情页 HTML
     * 自己不提供这个字段。
     */
    const val STUDENT_DETAIL = "student_detail/{userNum}/{name}/{department}"
    fun studentDetail(userNum: String, name: String, department: String) =
        "student_detail/${android.net.Uri.encode(userNum)}/${android.net.Uri.encode(name)}/${android.net.Uri.encode(department.ifEmpty { "-" })}"

    const val STUDENT_SCHEDULE = "student_schedule/{userNum}/{name}"
    fun studentSchedule(userNum: String, name: String) =
        "student_schedule/${android.net.Uri.encode(userNum)}/${android.net.Uri.encode(name)}"
}

data class TabSpec(
    val route: String,
    val labelRes: Int,
    val outlined: ImageVector,
    val filled: ImageVector,
)

private val TABS = listOf(
    TabSpec(Routes.SCHEDULE, R.string.nav_schedule, Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth),
    TabSpec(Routes.ANNOUNCEMENT, R.string.nav_announcement, Icons.Outlined.Notifications, Icons.Filled.Notifications),
    TabSpec(Routes.PROFILE, R.string.nav_profile, Icons.Outlined.Person, Icons.Filled.Person),
)

private val mainRouteSet = TABS.map { it.route }.toSet()

/** 转场按「淡入淡出」处理的路由：三个主 tab 互切 + 登录页进出（没有层级关系，滑动不合适）。 */
private val fadeRouteSet = mainRouteSet + Routes.LOGIN

private const val NAV_ANIM_MS = 260

/** 两端都是平级页（主 tab / 登录页）时 fade，否则视为「进入/退出子页面」走水平滑动。 */
private fun AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.isPeerNav(): Boolean =
    initialState.destination.route in fadeRouteSet && targetState.destination.route in fadeRouteSet

private fun AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.navEnter(): EnterTransition =
    if (isPeerNav()) fadeIn(tween(NAV_ANIM_MS))
    else slideInHorizontally(tween(NAV_ANIM_MS)) { it / 4 } + fadeIn(tween(NAV_ANIM_MS))

private fun AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.navExit(): ExitTransition =
    if (isPeerNav()) fadeOut(tween(NAV_ANIM_MS))
    else slideOutHorizontally(tween(NAV_ANIM_MS)) { -it / 5 } + fadeOut(tween(NAV_ANIM_MS))

private fun AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.navPopEnter(): EnterTransition =
    if (isPeerNav()) fadeIn(tween(NAV_ANIM_MS))
    else slideInHorizontally(tween(NAV_ANIM_MS)) { -it / 5 } + fadeIn(tween(NAV_ANIM_MS))

private fun AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.navPopExit(): ExitTransition =
    if (isPeerNav()) fadeOut(tween(NAV_ANIM_MS))
    else slideOutHorizontally(tween(NAV_ANIM_MS)) { it / 4 } + fadeOut(tween(NAV_ANIM_MS))

@Composable
fun AppNav() {
    // Splash 限时 800ms 兜底放行（见 MainActivity.SPLASH_MAX_WAIT_MS）—— 如果 sessionRestore
    // 这时还没回来（差网络冷启动常见），AppNav 自己显示 loading 占位继续等，**不**进入
    // NavHost。这是必须的：下面的 [startDestination] 用 `remember { ... }` 一次性计算，
    // 此时 AuthState 默认是 LoggedOut，会被锁死到登录页，sessionRestore 后续返回 LoggedIn
    // 也不会再切回 SCHEDULE。等到 sessionReady=true 再首次组合 NavHost 就避开了这个 race。
    val app = LocalContext.current.applicationContext as NvzhuanbanApp
    val sessionReady by produceState(initialValue = app.sessionRestore.isCompleted) {
        if (!value) {
            runCatching { app.sessionRestore.await() }
            value = true
        }
    }
    if (!sessionReady) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val nav = rememberNavController()
    val authRepo = AuthRepository.instance
    val backEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backEntry?.destination?.route
    val showBottomBar = currentRoute in mainRouteSet
    val sessionExpiredSignal by SessionEvents.expiredSignal.collectAsStateWithLifecycle()
    val authState by authRepo.state.collectAsStateWithLifecycle()
    val hasAnnouncementUnread by AnnouncementUnreadState.hasUnread
        .collectAsStateWithLifecycle(initialValue = false)

    // 启动时（Splash 屏 + 上面 sessionReady gate 已经等过 tryRestoreSession）：
    //   已登录 → 直接进课表
    //   未登录 → 进登录页
    // remember 确保 startDestination 只在首次 Composition 计算一次，避免回退栈被搅乱
    val startDestination = remember {
        when (AuthRepository.instance.state.value) {
            is AuthState.LoggedIn -> Routes.SCHEDULE
            else -> Routes.LOGIN
        }
    }

    // expiredSignal 是进程级、只增不减的计数器；Activity 重建后的新 Composition 会带着
    // 历史值重新执行 LaunchedEffect。若直接判 `> 0`，一次真实过期之后的任何一次重建
    // （旋转 / 深浅色 / 系统回收恢复）都会用陈旧信号把刚登录的有效会话再注销一遍。
    // 以首帧的当前值为基线，只对之后的**增量**反应。代价：Composition 不存在的区间
    // （重建全程）到达的信号会被基线吞掉 —— 但那种情况下会话本来就已失效，下一个
    // 走 *Auth 的业务请求会再次广播，最多延迟一拍，不会永久漏踢。
    var handledExpiredSignal by remember { mutableIntStateOf(SessionEvents.expiredSignal.value) }
    LaunchedEffect(sessionExpiredSignal) {
        if (sessionExpiredSignal <= handledExpiredSignal) return@LaunchedEffect
        handledExpiredSignal = sessionExpiredSignal
        // 用户已经在登录页时的信号是被踢出后 in-flight 请求补发的残留 ——
        // 只推进基线不再注销，避免把用户刚输完的登录状态搅乱。
        if (nav.currentBackStackEntry?.destination?.route == Routes.LOGIN) return@LaunchedEffect
        authRepo.expireSession()
    }

    LaunchedEffect(authState, currentRoute) {
        if (authState is AuthState.Error && currentRoute != null && currentRoute != Routes.LOGIN) {
            nav.navigate(Routes.LOGIN) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                BottomNav(
                    nav = nav,
                    currentRoute = currentRoute,
                    hasAnnouncementUnread = hasAnnouncementUnread,
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
            // 主 tab 之间（无层级）淡入淡出；进出子页面（有层级）水平滑动 + 淡入淡出。
            enterTransition = { navEnter() },
            exitTransition = { navExit() },
            popEnterTransition = { navPopEnter() },
            popExitTransition = { navPopExit() },
        ) {
            composable(Routes.LOGIN) {
                LoginScreen(
                    onLoggedIn = {
                        // launchSingleTop 防双重导航：登录成功瞬间 LoginScreen 里
                        // `LaunchedEffect(state.success)` 和 `LaunchedEffect(authState)` 两条路径
                        // 都会 fire 一次 onLoggedIn —— 没这个标志会在回退栈里多塞一个 SCHEDULE。
                        nav.navigate(Routes.SCHEDULE) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Routes.SCHEDULE) {
                ScheduleScreen(
                    onOpenExams = { nav.navigate(Routes.EXAMS) },
                    onOpenCourseOffering = { teacher, classroom, semesterIso ->
                        nav.navigate(Routes.courseOffering(teacher, classroom, semesterIso))
                    },
                )
            }
            composable(Routes.GRADES) {
                GradesScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.STUDENT_INFO) {
                StudentInfoScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.EXAMS) {
                ExamsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.ANNOUNCEMENT) {
                AnnouncementScreen(
                    onItemClick = { a -> nav.navigate(Routes.announcementDetail(a.id)) },
                )
            }
            composable(Routes.CLASSROOM) {
                ClassroomScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.TRAINING_PLAN) {
                TrainingPlanScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.CALENDAR) {
                CalendarScreen(
                    onBack = { nav.popBackStack() },
                    onOpenDocument = { entry ->
                        nav.navigate(Routes.calendarDocument(entry.url, entry.title))
                    },
                )
            }
            composable(
                route = Routes.CALENDAR_DOCUMENT,
                arguments = listOf(
                    androidx.navigation.navArgument("url") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("title") { type = androidx.navigation.NavType.StringType },
                ),
            ) { backStackEntry ->
                val url = backStackEntry.arguments?.getString("url").orEmpty()
                val title = backStackEntry.arguments?.getString("title").orEmpty()
                CalendarDocumentScreen(
                    url = url,
                    title = title,
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                route = Routes.COURSE_OFFERING,
                arguments = listOf(
                    androidx.navigation.navArgument("field") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = Routes.FIELD_NONE
                    },
                    androidx.navigation.navArgument("value") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = "-"
                    },
                    androidx.navigation.navArgument("semester") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = "-"
                    },
                ),
            ) { backStackEntry ->
                val field = backStackEntry.arguments?.getString("field").orEmpty()
                // path 参数已由 Navigation 自动解码，这里拿到的就是原始教师名 / 教室号
                val value = backStackEntry.arguments?.getString("value").orEmpty()
                    .takeUnless { it == "-" }.orEmpty()
                val semesterIso = backStackEntry.arguments?.getString("semester").orEmpty()
                    .takeUnless { it == "-" }.orEmpty()
                CourseOfferingScreen(
                    onBack = { nav.popBackStack() },
                    prefillTeacher = if (field == Routes.FIELD_TEACHER) value else "",
                    prefillClassroom = if (field == Routes.FIELD_CLASSROOM) value else "",
                    prefillSemesterIsoDate = semesterIso,
                )
            }
            composable(Routes.PEOPLE_SEARCH) {
                PeopleSearchScreen(
                    onBack = { nav.popBackStack() },
                    onOpenPerson = { result ->
                        when (result) {
                            is PersonResult.TeacherResult -> nav.navigate(
                                Routes.teacherDetail(result.userNum, result.name),
                            )
                            is PersonResult.StudentResult -> nav.navigate(
                                Routes.studentDetail(result.userNum, result.name, result.department),
                            )
                        }
                    },
                )
            }
            composable(
                route = Routes.TEACHER_DETAIL,
                arguments = listOf(
                    androidx.navigation.navArgument("userNum") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("name") { type = androidx.navigation.NavType.StringType },
                ),
            ) { backStackEntry ->
                val userNum = backStackEntry.arguments?.getString("userNum").orEmpty()
                val name = backStackEntry.arguments?.getString("name").orEmpty()
                TeacherDetailScreen(
                    userNum = userNum,
                    name = name,
                    onBack = { nav.popBackStack() },
                    onOpenSchedule = { nav.navigate(Routes.teacherSchedule(userNum, name)) },
                )
            }
            composable(
                route = Routes.TEACHER_SCHEDULE,
                arguments = listOf(
                    androidx.navigation.navArgument("userNum") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("name") { type = androidx.navigation.NavType.StringType },
                ),
            ) { backStackEntry ->
                val userNum = backStackEntry.arguments?.getString("userNum").orEmpty()
                val name = backStackEntry.arguments?.getString("name").orEmpty()
                UserScheduleScreen(
                    scheduleUrl = cn.jxnu.nvzhuanban.data.network.JxnuUrls.teacherScheduleUrl(userNum),
                    name = name,
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                route = Routes.STUDENT_DETAIL,
                arguments = listOf(
                    androidx.navigation.navArgument("userNum") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("name") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("department") { type = androidx.navigation.NavType.StringType },
                ),
            ) { backStackEntry ->
                val userNum = backStackEntry.arguments?.getString("userNum").orEmpty()
                val name = backStackEntry.arguments?.getString("name").orEmpty()
                val department = backStackEntry.arguments?.getString("department").orEmpty()
                    .takeUnless { it == "-" }.orEmpty()
                StudentDetailScreen(
                    userNum = userNum,
                    name = name,
                    department = department,
                    onBack = { nav.popBackStack() },
                    onOpenSchedule = { nav.navigate(Routes.studentSchedule(userNum, name)) },
                )
            }
            composable(
                route = Routes.STUDENT_SCHEDULE,
                arguments = listOf(
                    androidx.navigation.navArgument("userNum") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("name") { type = androidx.navigation.NavType.StringType },
                ),
            ) { backStackEntry ->
                val userNum = backStackEntry.arguments?.getString("userNum").orEmpty()
                val name = backStackEntry.arguments?.getString("name").orEmpty()
                UserScheduleScreen(
                    scheduleUrl = cn.jxnu.nvzhuanban.data.network.JxnuUrls.studentScheduleUrl(userNum),
                    name = name,
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.PROFILE) {
                ProfileScreen(
                    onLogout = {
                        nav.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onOpenGrades = { nav.navigate(Routes.GRADES) },
                    onOpenStudentInfo = { nav.navigate(Routes.STUDENT_INFO) },
                    onOpenClassroom = { nav.navigate(Routes.CLASSROOM) },
                    onOpenExams = { nav.navigate(Routes.EXAMS) },
                    onOpenTrainingPlan = { nav.navigate(Routes.TRAINING_PLAN) },
                    onOpenPeopleSearch = { nav.navigate(Routes.PEOPLE_SEARCH) },
                    onOpenCalendar = { nav.navigate(Routes.CALENDAR) },
                    onOpenCourseOffering = { nav.navigate(Routes.courseOffering()) },
                )
            }
            composable(
                route = Routes.ANNOUNCEMENT_DETAIL,
                arguments = listOf(androidx.navigation.navArgument("articleId") {
                    type = androidx.navigation.NavType.StringType
                }),
            ) { backStackEntry ->
                val articleId = backStackEntry.arguments?.getString("articleId").orEmpty()
                AnnouncementDetailScreen(
                    articleId = articleId,
                    onBack = { nav.popBackStack() },
                    // 「需要登录」占位页的「去登录」：清空回退栈进 app 内登录页，
                    // 与会话过期那条 (LaunchedEffect(authState)) 保持一致写法。
                    onNavigateToLogin = {
                        nav.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BottomNav(
    nav: NavHostController,
    currentRoute: String?,
    hasAnnouncementUnread: Boolean,
) {
    NavigationBar {
        TABS.forEach { tab ->
            val selected = currentRoute == tab.route
            val showDot = tab.route == Routes.ANNOUNCEMENT && hasAnnouncementUnread
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        nav.navigate(tab.route) {
                            // 锚定 SCHEDULE 而非 graph.findStartDestination()：登录页冷启动的会话里
                            // startDestination 是 LOGIN，登录成功后已被 popUpTo(inclusive) 弹掉，
                            // 按 id 找锚点会永远 pop 不到 —— 每次切 tab 都往栈里压新实例，
                            // saveState/restoreState 全失效（滚动位置丢、返回键倒退一长串）。
                            // 登录成功与已登录冷启动两条路径的栈底都恒为 SCHEDULE。
                            popUpTo(Routes.SCHEDULE) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = {
                    val icon: @Composable () -> Unit = {
                        Icon(
                            imageVector = if (selected) tab.filled else tab.outlined,
                            contentDescription = null,
                        )
                    }
                    if (showDot) {
                        // 红点纯视觉，TalkBack 靠这条 contentDescription 得知有未读；无红点时不加
                        BadgedBox(
                            badge = { Badge() },
                            modifier = Modifier.semantics { contentDescription = "有未读通知" },
                        ) { icon() }
                    } else {
                        icon()
                    }
                },
                label = { Text(stringResource(tab.labelRes)) },
                alwaysShowLabel = false,
            )
        }
    }
}
