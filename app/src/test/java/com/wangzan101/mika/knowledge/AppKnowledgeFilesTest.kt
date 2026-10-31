package com.wangzan101.mika.knowledge

import com.wangzan101.mika.core.knowledge.AppKnowledgeDraft
import com.wangzan101.mika.core.knowledge.AppKnowledgeStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppKnowledgeFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val facts = "- 首页顶部有搜索入口。\n- 「我的」页里有设置。"
    private fun draft(id: String, md: String = facts, lesson: Boolean = false, revises: String? = null) =
        AppKnowledgeDraft(id, "qq", "com.tencent.mobileqq", md, "run-$id", AppKnowledgeStage.Draft, 1, lesson = lesson, revises = revises)

    @Test fun draftTrialVerifiedAndArchive() {
        val files = AppKnowledgeFiles(tmp.root)
        assertTrue(files.putPending(draft("d1")))
        // 同一个 App 至多一份待确认的
        assertFalse(files.putPending(draft("d2")))
        assertTrue(files.notes().isEmpty())
        assertTrue(File(tmp.root, "knowledge/app-drafts/d1/APP.md").isFile)
        assertTrue(files.approve("d1"))
        assertTrue(files.notes().getValue("qq").trial)
        assertEquals(facts, files.notes().getValue("qq").text)
        assertEquals(AppKnowledgeStage.Verified, files.recordTrial("qq", true)?.stage)
        assertFalse(files.notes().getValue("qq").trial)
        assertTrue(File(tmp.root, "knowledge/app-drafts/archive").listFiles().orEmpty().isNotEmpty())
    }

    @Test fun revisionReplacesTheOneInUse() {
        val files = AppKnowledgeFiles(tmp.root)
        files.putPending(draft("d1")); files.approve("d1"); files.recordTrial("qq", true)
        // 在用的那份不挡新的修订
        assertTrue(files.putPending(draft("d2", "$facts\n- 消息页右上角的加号能建群。", revises = "d1")))
        assertEquals(facts, files.notes().getValue("qq").text)
        assertTrue(files.approve("d2"))
        assertEquals(AppKnowledgeStage.Archived, files.all().single { it.id == "d1" }.stage)
        assertEquals("d2", files.inUse("qq")?.id)
        assertTrue(files.notes().getValue("qq").text.contains("加号"))
    }

    @Test fun failedTrialIsArchivedAndNeverInjected() {
        val files = AppKnowledgeFiles(tmp.root)
        assertTrue(files.putPending(draft("d1")))
        files.approve("d1")
        assertEquals(AppKnowledgeStage.Archived, files.recordTrial("qq", false)?.stage)
        assertTrue(files.notes().isEmpty())
        assertEquals(AppKnowledgeStage.Archived, AppKnowledgeFiles(tmp.root).all().single().stage)
    }

    @Test fun lessonSurvivesOneBadTrial() {
        val files = AppKnowledgeFiles(tmp.root)
        files.putPending(draft("d1", lesson = true)); files.approve("d1")
        assertEquals(AppKnowledgeStage.Trial, files.recordTrial("qq", false)?.stage)
        assertTrue(AppKnowledgeFiles(tmp.root).all().single().lesson)
        assertEquals(AppKnowledgeStage.Archived, files.recordTrial("qq", false)?.stage)
        assertNull(files.inUse("qq"))
    }

    @Test fun olderFullAppMdDraftsInjectOnlyTheirBody() {
        val files = AppKnowledgeFiles(tmp.root)
        files.putPending(draft("d1", "---\nname: qq\npackages: [com.tencent.mobileqq]\n---\n## 是什么\nQQ 应用。")); files.approve("d1")
        assertEquals("## 是什么\nQQ 应用。", files.notes().getValue("qq").text)
    }
}
