package cn.jxnu.nvzhuanban.data.model

/**
 * 教学周历（俗称校历）索引页的一条目。
 *
 * 数据来源 `https://jwc.jxnu.edu.cn/Jxzl_Index.htm`：
 * 顶部一个「万年历」(`wnl.htm`)，下面按学年由新到旧排列每学期 PDF/DOC/XLS/JPG/HTM 文件链接。
 * 2018 年以后是 PDF；更早的几年用 .doc/.xls/.jpg 留存；2009 年前甚至是按整学年合并的 .htm。
 * 2019-2020 第二学期由于疫情有一份「(调整)」版本，jwc 用蓝色按钮（`bg-blue`）和红色（`bg-red`）区分。
 */
data class CalendarEntry(
    val title: String,
    val url: String,
    val fileType: CalendarFileType,
    val isPerpetual: Boolean,
    val isCorrection: Boolean,
)

/** 校历文件的实际格式。决定 UI 上挂哪个图标，以及点击后系统会调起哪类 viewer。 */
enum class CalendarFileType { PDF, DOC, XLS, JPG, HTM, OTHER }
