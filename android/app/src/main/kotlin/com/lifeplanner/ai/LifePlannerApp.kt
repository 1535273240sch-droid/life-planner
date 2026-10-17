package com.lifeplanner.ai

import android.app.Application
import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.lifeplanner.ai.data.SettingsStore
import com.lifeplanner.ai.net.OkHttpTransport
import com.lifeplanner.core.ai.AiService
import com.lifeplanner.core.data.AiMessageRepositoryImpl
import com.lifeplanner.core.data.DailyLogRepositoryImpl
import com.lifeplanner.core.data.GoalRepositoryImpl
import com.lifeplanner.core.data.ProfileRepositoryImpl
import com.lifeplanner.core.data.SkillRepositoryImpl
import com.lifeplanner.core.data.TaskRepositoryImpl
import com.lifeplanner.core.db.LifePlannerDb
import com.lifeplanner.core.json.BackupService
import com.lifeplanner.core.logic.GoalService
import com.lifeplanner.core.logic.LogService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手动依赖容器（单用户本地应用，不引入 DI 框架）。
 *
 * 约定：
 * - 仓储与 Service 都是无状态的，整个进程共享一份即可。
 * - [aiService] 每次调用都会重新读取设置：用户改了 Key/模型后立即生效，
 *   不会拿到启动时的旧配置。
 * - 所有仓储方法都是阻塞 IO；界面层必须切到 [io] 或 Dispatchers.IO 再调用。
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    private val driver = AndroidSqliteDriver(
        schema = LifePlannerDb.Schema,
        context = appContext,
        name = DB_NAME,
    )

    val db: LifePlannerDb = LifePlannerDb(driver)

    val settings = SettingsStore(appContext)

    val profileRepository = ProfileRepositoryImpl(db)
    val skillRepository = SkillRepositoryImpl(db)
    val goalRepository = GoalRepositoryImpl(db)
    val taskRepository = TaskRepositoryImpl(db)
    val dailyLogRepository = DailyLogRepositoryImpl(db)
    val aiMessageRepository = AiMessageRepositoryImpl(db)

    val goalService = GoalService(goalRepository, taskRepository)
    val logService = LogService(dailyLogRepository, goalRepository, taskRepository)

    val backupService = BackupService(
        profileRepository = profileRepository,
        skillRepository = skillRepository,
        goalRepository = goalRepository,
        taskRepository = taskRepository,
        dailyLogRepository = dailyLogRepository,
        aiMessageRepository = aiMessageRepository,
    )

    private val transport = OkHttpTransport()

    /** 使用当前设置构造 AI 服务；未配置 Key 时调用会抛 [com.lifeplanner.core.ai.AiException]。 */
    fun aiService(): AiService = AiService(
        transport = transport,
        config = settings.loadAiConfig(),
        profileRepo = profileRepository,
        skillRepo = skillRepository,
        goalRepo = goalRepository,
        taskRepo = taskRepository,
        logRepo = dailyLogRepository,
        aiMessageRepo = aiMessageRepository,
    )

    /** 界面层共享的 IO 协程作用域（带 SupervisorJob，单个页面失败不影响其他页面）。 */
    val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val DB_NAME = "life_planner_v2.db"
    }
}

/** 应用入口：进程内只创建一次容器。 */
class LifePlannerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 先装崩溃记录，再创建容器：容器初始化失败也能留下堆栈。
        CrashLogger.install(this)
        container = AppContainer(this)
    }
}
