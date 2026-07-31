package cn.jxnu.nvzhuanban.data.network

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore
import javax.crypto.BadPaddingException

/**
 * 加密凭证存储。仅在用户勾选「记住账号」时存学号 + 密码。
 *
 * 实现：[EncryptedSharedPreferences] + Android Keystore 硬件密钥。
 *  - 主密钥用 AES256_GCM，由系统 Keystore 生成并托管，App 拿不到原始字节
 *  - 文件内容 AES256_GCM 加密，键名 AES256_SIV 加密
 *  - App 被卸载时密钥会一并销毁，重装后即使数据保留也无法解密
 *
 * 设计权衡：
 *  - 原本 [AuthStorage] 注释里说"故意不存密码"——那是 Phase 2 的保守路线
 *  - Phase 3 要做免登录，cookie 总会失效（教务 30 分钟无活动就掉），不存密码就做不到无感
 *  - 用 EncryptedSharedPreferences 是 Google 官方推荐的标准方案，比自己 RSA 加密更安全
 *
 * **故障分类**：Android Keystore 在真机上有大量**瞬时**故障形态（开机早期服务
 * 未就绪、backend busy、binder 抖动，多以 KeyStoreException / ProviderException 现身）。
 * 旧实现把任何创建失败都当「文件永久损坏」立即删凭证文件 + 重置 master key——一次开机抖动
 * 就把用户保存的密码毁掉，正是「偶发掉登录且凭证消失」的来源。现在：
 *  - 仅 **确定性密文/密钥损坏**（[isDeterministicCorruption]：AEADBadTag/BadPadding 谱系、
 *    Tink keyset proto 损坏——Auto Backup 迁移到新设备后必现的形态）才立即走破坏性修复阶梯；
 *  - 其余异常一律视为瞬时：本次返回 null 不删任何东西，短退避后允许重试；
 *  - 兜底未知厂商异常实为永久损坏：**连续 [REPAIR_AFTER_STREAK] 个进程**都创建失败才升级
 *    执行修复阶梯（计数存明文 meta prefs，只有整数无敏感数据；创建成功即清零）。
 *
 * 注意：root 设备理论上能拿到 keystore 的密钥（如果攻击者已物理控制设备）；
 * 但即便如此，密码也是用 CAS RSA 公钥再加密一次才发出去，泄露风险有限。
 */
class SecureCredentialStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefsLock = Any()

    /** 创建成功后的缓存。失败**不**缓存（瞬时 Keystore 故障下次访问可重试），见 [prefsOrNull]。 */
    @Volatile private var cachedPrefs: SharedPreferences? = null

    /** 最近一次创建失败是否「确定性损坏且修复阶梯也救不回」。瞬时失败恒 false。 */
    @Volatile private var lastFailurePermanent = false

    /** 上次创建失败的时间戳（elapsedRealtime）；退避窗口内不再打 Keystore，防主线程反复卡顿。 */
    @Volatile private var lastFailedAttemptAt = 0L

    /** 跨进程失败计数每进程只 +1（进程内的重试不该刷爆计数）。 */
    @Volatile private var failureCountedThisProcess = false

    /** 明文 meta prefs：只存「连续失败进程数」这一个整数，无敏感数据。 */
    private val metaPrefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(META_FILE, Context.MODE_PRIVATE)
    }

    /**
     * 取（或创建）加密 prefs。与旧 `by lazy` 的关键差异：**失败不被钉死**——lazy 会把首次
     * 瞬时失败的 null 缓存到进程结束，连锁后果是用户手动登录后 save() 也失败、上层把
     * rememberMe 永久关闭；现在成功才缓存，失败经 [RETRY_BACKOFF_MS] 退避后可重试。
     */
    private fun prefsOrNull(): SharedPreferences? {
        cachedPrefs?.let { return it }
        synchronized(prefsLock) {
            cachedPrefs?.let { return it }
            if (SystemClock.elapsedRealtime() - lastFailedAttemptAt < RETRY_BACKOFF_MS &&
                lastFailedAttemptAt != 0L
            ) {
                return null
            }
            // 历史明文兜底文件的清理跟随首次访问（tryRestoreSession 的 IO 协程）执行，
            // 不放构造器 —— 构造器在 AuthRepository.init 的主线程冷启动路径上。
            clearLegacyPlaintextFallback(appContext)
            val created = createEncryptedPrefsOrNull(appContext)
            if (created != null) {
                cachedPrefs = created
                lastFailurePermanent = false
                failureCountedThisProcess = false
                metaPrefs.edit().remove(K_FAIL_STREAK).apply()
            } else {
                lastFailedAttemptAt = SystemClock.elapsedRealtime()
            }
            return created
        }
    }

    /** 保存账号密码，调用前应确认 rememberMe == true。 */
    fun save(username: String, password: String): Boolean {
        val p = prefsOrNull() ?: return false
        return p.edit()
            .putString(K_USERNAME, username)
            .putString(K_PASSWORD, password)
            .commit()
    }

    /** 同时拿到学号 + 密码；任一为空返回 null。 */
    fun load(): Credentials? {
        val p = prefsOrNull() ?: return null
        val u = runCatching { p.getString(K_USERNAME, null) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val password = runCatching { p.getString(K_PASSWORD, null) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return Credentials(u, password)
    }

    fun hasCredentials(): Boolean = load() != null

    fun isAvailable(): Boolean = prefsOrNull() != null

    /**
     * [save] 返回 false 时供上层（[cn.jxnu.nvzhuanban.data.repository.AuthRepository]）区分去留：
     * true = 确定性损坏且修复阶梯也救不回 → 诚实关掉 rememberMe；
     * false = 瞬时 Keystore 故障 → 保留 rememberMe，下次登录自然重试。
     */
    fun isPermanentlyBroken(): Boolean = lastFailurePermanent

    fun clear() {
        val p = prefsOrNull()
        if (p != null) {
            p.edit().clear().apply()
        } else {
            // prefs 拿不到（Keystore 瞬时故障）也必须把密码毁掉——logout 语义优先：
            // 直接删加密文件，密文没了即使 Keystore 稍后恢复也解不出旧密码。
            // 否则「登出 → 抖动清空失败 → 下次冷启动 Keystore 恢复」会把已登出用户静默复活。
            clearEncryptedPrefsFile(appContext)
        }
        clearLegacyPlaintextFallback(appContext)
    }

    data class Credentials(val username: String, val password: String)

    /**
     * 创建（或修复）加密 prefs：
     *  1. 直接创建，成功即返回；
     *  2. 失败 → 分类：确定性损坏（或连续失败进程数达阈值）才走破坏性修复阶梯——
     *     删 prefs 文件重建 → 仍失败再重置 master key + 删文件重建；
     *  3. 瞬时失败 → 返回 null，**不删任何东西**（凭证文件完好，等 Keystore 恢复）。
     *
     * 修复阶梯的两级语义保留自旧实现：Auto Backup / 换机迁移会留下本安装解不开的密文文件
     * （删文件即可修）；master key alias 本身坏掉则需重置（全仓仅本类使用默认 MasterKey，
     * 重置是受控修理）。
     */
    private fun createEncryptedPrefsOrNull(context: Context): SharedPreferences? {
        val first = createEncryptedPrefs(context)
        first.getOrNull()?.let { return it }

        val shouldRepair = isDeterministicCorruption(first.exceptionOrNull()) ||
            recordFailureAndCheckStreak()
        if (!shouldRepair) {
            lastFailurePermanent = false
            return null
        }

        clearEncryptedPrefsFile(context)
        createEncryptedPrefs(context).getOrNull()?.let { return it }

        resetMasterKey()
        clearEncryptedPrefsFile(context)
        val repaired = createEncryptedPrefs(context).getOrNull()
        lastFailurePermanent = repaired == null
        return repaired
    }

    /**
     * 是否「确定性密文/密钥损坏」——重试永远不会好，删文件重建是唯一出路：
     *  - [BadPaddingException]（含子类 AEADBadTagException）：密文与密钥不匹配，
     *    Auto Backup 恢复到新设备（Keystore 密钥不随备份走）的必现形态；
     *  - Tink 的 keyset proto 损坏：InvalidProtocolBufferException，注意它继承 IOException
     *    而非 GeneralSecurityException，且藏在 shaded 包路径（com.google.crypto.tink.shaded.*）
     *    ——按类名匹配，避免编译期依赖 shaded 包。
     * KeyStoreException / ProviderException **刻意不在白名单**：它们虽是安全异常，但在真机上
     * 大量是瞬时形态（开机早期 keystore 服务未就绪、backend busy），交给失败计数兜底。
     * Tink / EncryptedSharedPreferences 会层层包装异常，必须展开 cause 链找。
     */
    private fun isDeterministicCorruption(t: Throwable?): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < MAX_CAUSE_DEPTH) {
            if (cur is BadPaddingException) return true
            if (cur::class.java.simpleName == "InvalidProtocolBufferException") return true
            cur = cur.cause
            depth++
        }
        return false
    }

    /**
     * 未知异常的兜底升级：把本进程的失败记入跨进程连续失败计数（每进程至多 +1），
     * 连续 [REPAIR_AFTER_STREAK] 个进程都失败 → 认定永久损坏、允许走修复阶梯。
     * 把「未知厂商异常导致凭证存储永久不可用」的上界压到 N 次启动，同时单次抖动绝不毁凭证。
     */
    private fun recordFailureAndCheckStreak(): Boolean {
        if (!failureCountedThisProcess) {
            failureCountedThisProcess = true
            val streak = metaPrefs.getInt(K_FAIL_STREAK, 0) + 1
            metaPrefs.edit().putInt(K_FAIL_STREAK, streak).apply()
            return streak >= REPAIR_AFTER_STREAK
        }
        return metaPrefs.getInt(K_FAIL_STREAK, 0) >= REPAIR_AFTER_STREAK
    }

    private fun createEncryptedPrefs(context: Context): Result<SharedPreferences> = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun clearEncryptedPrefsFile(context: Context) {
        runCatching {
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
        runCatching {
            val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            File(sharedPrefsDir, "$FILE_NAME.xml").delete()
            File(sharedPrefsDir, "$FILE_NAME.xml.bak").delete()
        }
    }

    private fun resetMasterKey() {
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        }
    }

    private fun clearLegacyPlaintextFallback(context: Context) {
        context.getSharedPreferences("${FILE_NAME}_fallback", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    companion object {
        private const val FILE_NAME = "jxnu_secure_creds"
        /** 明文 meta（仅失败计数）。不在 NvzhuanbanApp.STARTUP_PREFS_FILES 里：正常冷启动不读它。 */
        private const val META_FILE = "jxnu_secure_creds_meta"
        private const val K_USERNAME = "u"
        private const val K_PASSWORD = "p"
        private const val K_FAIL_STREAK = "create_fail_streak"
        private const val MAX_CAUSE_DEPTH = 8
        private const val REPAIR_AFTER_STREAK = 3
        private const val RETRY_BACKOFF_MS = 5_000L

        @Volatile private var INSTANCE: SecureCredentialStore? = null

        fun init(context: Context): SecureCredentialStore {
            INSTANCE?.let { return it }
            return synchronized(this) {
                INSTANCE ?: SecureCredentialStore(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun get(): SecureCredentialStore = INSTANCE
            ?: error("SecureCredentialStore 未初始化")
    }
}
