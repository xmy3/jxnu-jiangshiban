package cn.jxnu.nvzhuanban.data.work

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import cn.jxnu.nvzhuanban.data.model.Exam
import cn.jxnu.nvzhuanban.data.storage.ExamReminderStore
import java.lang.reflect.Proxy
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamReminderSchedulerTest {
    private val date = LocalDate.of(2026, 1, 9)
    private val before = date.minusDays(1).atTime(19, 59)
    private val delayed = date.minusDays(1).atTime(20, 1)
    private val exam = Exam("exam", "高数", "CS101", date.atStartOfDay(), location = "B203")

    @Test
    fun `refresh keeps the receiver archive until delayed delivery but never recreates it afterwards`() {
        val context = memoryContext()
        ExamReminderScheduler.sync(context, listOf(exam), before)
        ExamReminderScheduler.sync(context, listOf(exam.copy(location = "B204")), delayed)
        val pending = ExamReminderStore.load(context)
        assertEquals(listOf(date), pending.map { it.date })
        assertEquals("B204", pending.single().items.single().location)

        // 接收器投递时摘掉存档；后续轮询不应重新添加已发送的提醒。
        ExamReminderStore.remove(context, date)
        ExamReminderScheduler.sync(context, listOf(exam), delayed.plusMinutes(1))
        assertTrue(ExamReminderStore.load(context).isEmpty())
    }

    @Test
    fun `rescheduling and cancelling still remove obsolete receiver archives`() {
        val context = memoryContext()
        ExamReminderScheduler.sync(context, listOf(exam), before)
        ExamReminderScheduler.sync(context, listOf(exam.copy(startTime = date.plusDays(1).atStartOfDay())), delayed)
        assertEquals(listOf(date.plusDays(1)), ExamReminderStore.load(context).map { it.date })
        ExamReminderScheduler.sync(context, emptyList(), delayed)
        assertTrue(ExamReminderStore.load(context).isEmpty())
    }

    // 使用真实调度器/JSON 存档，替换磁盘存储；平台 AlarmManager 的实际投递仍需设备验证。
    private fun memoryContext(): Context {
        val values = mutableMapOf<String, String>()
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> { values[args!![0] as String] = args[1] as String; proxy }
                "remove" -> { values.remove(args!![0] as String); proxy }
                "apply" -> null
                else -> error(method.name)
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0]] ?: args[1]
                "edit" -> editor
                else -> error(method.name)
            }
        } as SharedPreferences
        return object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
            override fun getSystemService(name: String): Any? = null
        }
    }
}
