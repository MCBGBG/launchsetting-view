package imizuki.launchsettingview

import tools.jackson.core.PrettyPrinter
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.core.util.DefaultIndenter
import tools.jackson.core.util.DefaultPrettyPrinter
import tools.jackson.core.util.Separators
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path

/**
 * launchSettings.json 的 JSON 树读写封装。
 *
 * 使用容错 mapper（允许注释/尾逗号，与 IDE 解析行为一致）。
 * 表单只更新已知字段，其余内容（$schema、iisSettings、profile 自定义键等）
 * 原样保留，避免结构化编辑丢数据。
 */
object LaunchSettingsJsonEditor {

    const val KEY_PROFILES = "profiles"
    const val KEY_COMMAND_NAME = "commandName"
    const val KEY_COMMAND_LINE_ARGS = "commandLineArgs"
    const val KEY_EXECUTABLE_PATH = "executablePath"
    const val KEY_WORKING_DIRECTORY = "workingDirectory"
    const val KEY_APPLICATION_URL = "applicationUrl"
    const val KEY_LAUNCH_BROWSER = "launchBrowser"
    const val KEY_LAUNCH_URL = "launchUrl"
    const val KEY_NATIVE_DEBUGGING = "nativeDebugging"
    const val KEY_ENVIRONMENT_VARIABLES = "environmentVariables"

    /** UTF-8 BOM（dotnet new 生成的文件常带） */
    private const val BOM = '﻿'

    /** 容错 mapper：允许 JSONC 注释与尾逗号（VS 生成的 launchSettings.json 常见） */
    private val mapper: JsonMapper by lazy {
        JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_YAML_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build()
    }

    /** 与 dotnet/VS 一致的输出格式："key": value，2 空格缩进 */
    private val prettyPrinter: PrettyPrinter = DefaultPrettyPrinter()
        .withSeparators(
            Separators.createDefaultInstance()
                .withObjectNameValueSpacing(Separators.Spacing.AFTER)
                .withObjectEntrySpacing(Separators.Spacing.NONE)
        )
        .withObjectIndenter(DefaultIndenter("  ", "\n"))
        .withArrayIndenter(DefaultIndenter("  ", "\n"))

    fun load(path: Path): JsonNode = mapper.readTree(Files.readString(path).trimStart(BOM))

    /** 新建文件的模板 */
    fun newDocument(): ObjectNode =
        JsonNodeFactory.instance.objectNode().apply {
            put("\$schema", "https://json.schemastore.org/launchsettings.json")
            set(KEY_PROFILES, JsonNodeFactory.instance.objectNode())
        }

    /** 取根下的 profiles 对象；结构不符返回 null */
    fun profilesOf(root: JsonNode): ObjectNode? {
        if (!root.isObject) return null
        val profiles = root.get(KEY_PROFILES) ?: return null
        if (!profiles.isObject) return null
        return profiles as ObjectNode
    }

    /** 序列化：pretty 格式 + 末尾换行（与 dotnet 工具链一致） */
    fun serialize(root: JsonNode): String =
        mapper.writer().with(prettyPrinter).writeValueAsString(root) + "\n"
}
