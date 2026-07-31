package cn.jxnu.nvzhuanban.data.network.pages

/**
 * 测试 fixture 读取工具：统一从 classpath 资源 `/samples/<name>` 加载，
 * 避免依赖工作目录 / 项目根 `/samples/` 路径（后者存放真实 PII，已不在仓库内）。
 *
 * 所有脱敏 fixture 位于 `app/src/test/resources/samples/`，姓名 / 学号 / 学院
 * 已替换为示例值；测试断言里的具体姓名（张三 / 李四 等）和数字都对应这些
 * fixture，改 fixture 时记得同步 test。
 */
internal fun sampleHtml(name: String): String {
    val stream = object {}.javaClass.getResourceAsStream("/samples/$name")
        ?: error("找不到测试资源 /samples/$name")
    return stream.use { it.readBytes().toString(Charsets.UTF_8) }
}

/**
 * 同样从 `/samples/` 读，仅作为语义化别名给 JSON / 其他文本 fixture 用。
 * 实现完全等同于 [sampleHtml] —— 都是 UTF-8 文本。
 */
internal fun sampleJson(name: String): String = sampleHtml(name)
