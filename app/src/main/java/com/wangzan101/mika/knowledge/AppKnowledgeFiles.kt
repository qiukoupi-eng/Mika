package com.wangzan101.mika.knowledge

import com.wangzan101.mika.core.knowledge.AppKnowledgeDraft
import com.wangzan101.mika.core.knowledge.AppKnowledgeDraftStore
import com.wangzan101.mika.core.knowledge.AppKnowledgeStage
import com.wangzan101.mika.core.knowledge.AppKnowledgeTrial
import com.wangzan101.mika.core.knowledge.LearnedNote
import com.wangzan101.mika.core.knowledge.FrontMatter
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * App 知识补充的各个版本与阶段账本;待确认的(草稿)不会被知识库读取。每个 App 至多一份待确认的;
 * 收下一份就把这个 App 在用的那份归档(改动前的文件都留在 archive 里)。
 */
class AppKnowledgeFiles(dir: File) : AppKnowledgeDraftStore {
    private val root = File(dir, "knowledge/app-drafts")
    private val archive = File(root, "archive")
    private val journal = File(root, "changes.jsonl")
    private val idPattern = Regex("[A-Za-z0-9_-]+")

    @Synchronized override fun all(): List<AppKnowledgeDraft> =
        root.listFiles { f -> f.isDirectory && f.name != "archive" && idPattern.matches(f.name) }.orEmpty()
            .mapNotNull { d ->
                runCatching {
                    val row = Json.decodeFromString(AppKnowledgeDraft.serializer(), File(d, "meta.json").readText())
                    row.copy(markdown = File(d, "APP.md").readText())
                }.getOrNull()
            }

    @Synchronized override fun putPending(draft: AppKnowledgeDraft): Boolean {
        if (!idPattern.matches(draft.id) || !idPattern.matches(draft.appName) ||
            draft.stage != AppKnowledgeStage.Draft ||
            all().any { it.appName == draft.appName && it.stage == AppKnowledgeStage.Draft }) return false
        save(draft)
        return true
    }

    @Synchronized override fun approve(id: String): Boolean {
        val rows = all()
        val old = rows.firstOrNull { it.id == id && it.stage == AppKnowledgeStage.Draft } ?: return false
        rows.filter { it.appName == old.appName && it.id != id && it.stage != AppKnowledgeStage.Archived }
            .forEach { save(it.copy(stage = AppKnowledgeStage.Archived)) }
        save(old.copy(stage = AppKnowledgeStage.Trial))
        return true
    }

    @Synchronized override fun reject(id: String): Boolean {
        val old = all().firstOrNull { it.id == id && it.stage != AppKnowledgeStage.Archived } ?: return false
        save(old.copy(stage = AppKnowledgeStage.Archived))
        return true
    }

    @Synchronized override fun recordTrial(appName: String, good: Boolean): AppKnowledgeDraft? {
        val old = all().firstOrNull { it.appName == appName && it.stage == AppKnowledgeStage.Trial } ?: return null
        val next = AppKnowledgeTrial.next(old, good)
        save(next)
        return next
    }

    /** 在用的补充;旧版本起草的是带头部的整份 APP.md,只取正文。 */
    @Synchronized override fun notes(): Map<String, LearnedNote> =
        all().filter { it.stage == AppKnowledgeStage.Trial || it.stage == AppKnowledgeStage.Verified }
            .associate { row -> row.appName to LearnedNote(FrontMatter.parse(row.markdown).second.trim(), trial = row.stage == AppKnowledgeStage.Trial) }

    private fun save(row: AppKnowledgeDraft) {
        val d = File(root, row.id)
        if (d.exists()) {
            val revision = UUID.randomUUID().toString()
            d.copyRecursively(File(archive, revision), overwrite = true)
            journal.parentFile.mkdirs()
            journal.appendText(revision + " " + row.id + " " + row.stage + " " + System.currentTimeMillis() + "\n")
        }
        d.mkdirs()
        atomic(File(d, "APP.md"), row.markdown)
        atomic(File(d, "meta.json"), Json.encodeToString(AppKnowledgeDraft.serializer(), row.copy(markdown = "")))
    }

    private fun atomic(file: File, content: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
