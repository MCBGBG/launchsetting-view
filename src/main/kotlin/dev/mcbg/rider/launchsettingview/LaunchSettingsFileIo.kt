package dev.mcbg.rider.launchsettingview

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil
import com.jetbrains.rider.run.configurations.launchSettings.LaunchSettingsJsonService
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * launchSettings.json 的路径推导、读取与 VFS 感知保存。
 */
object LaunchSettingsFileIo {

    /**
     * 由 .csproj 路径推导 launchSettings.json 路径。
     * 复用 Rider 自己的约定实现：<csproj目录>/Properties/launchSettings.json。
     */
    fun launchSettingsPath(csprojPath: String): Path =
        LaunchSettingsJsonService.Companion.getLaunchSettingsFileForProject(Path.of(csprojPath))
            ?: throw IOException("无法推导 launchSettings.json 路径：$csprojPath")

    fun read(file: Path): String = Files.readString(file, StandardCharsets.UTF_8)

    /**
     * 保存文本到文件（EDT 上调用；KB 级文件无需后台线程）。
     * 创建模式会先建 Properties/ 目录与空文件，再走 VFS 写入，
     * 这样 Rider 的 LaunchSettingsJsonCachedValueProvider 缓存会自动失效。
     * @throws IOException 写入失败时抛出，由调用方弹错误提示
     */
    fun save(file: Path, text: String) {
        Files.createDirectories(file.parent)
        if (!Files.exists(file)) {
            Files.createFile(file)
        }
        val vFile: VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)
            ?: throw IOException("无法访问文件：$file")

        val document = FileDocumentManager.getInstance().getDocument(vFile)
        if (document != null) {
            // 文件已在编辑器中打开：写透 Document，保持打开的编辑器页一致
            ApplicationManager.getApplication().runWriteAction { document.setText(text) }
            FileDocumentManager.getInstance().saveDocument(document)
        } else {
            // 纯 VFS 写入，会发出 VFS 变更事件
            ApplicationManager.getApplication().runWriteAction { VfsUtil.saveText(vFile, text) }
        }
    }
}
