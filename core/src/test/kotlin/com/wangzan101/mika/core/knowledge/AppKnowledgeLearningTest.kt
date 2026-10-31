package com.wangzan101.mika.core.knowledge

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.assembly.knownTools
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppKnowledgeLearningTest {
    private val store = KnowledgeStore(KnowledgeStore.loadBundled())
    private val app = store.byPackage("com.taobao.idlefish")!!
    private val facts = "- 首页顶部的搜索框进入搜索页,结果分商品和用户两个标签。\n- 「我的」页里的「我发布的」列出自己挂出的商品。"
    private val unreviewed = TaskState.Succeeded("已查看", verified = false, reviewed = false)

    /** [results] 每一步的结果;默认都生效。 */
    private fun events(vararg results: String = Array(5) { """{"ok":true}""" }, extra: List<RunEvent> = emptyList()): List<RunEvent> {
        val out = mutableListOf<RunEvent>()
        val names = listOf("phone_launch_app", "phone_tap", "phone_tap", "phone_scroll", "phone_tap", "phone_back", "phone_back")
        results.forEachIndexed { i, r ->
            val name = names[i]
            out += RunEvent.ModelReply(i.toLong(), i + 1, "", name,
                if (i == 0) """{"package":"com.taobao.idlefish"}""" else """{"x":500,"y":500}""", null, null, null)
            out += RunEvent.ToolResult((i + 20).toLong(), i + 1, name, r, !r.contains("false") && !r.contains("unchanged"), null)
        }
        return out + extra
    }

    private fun model(reply: String) = FakeModel(ModelReply(listOf(Part.Text(reply)), StopReason.EndTurn, null))
    private fun request(m: FakeModel) = m.requests.single().single().parts.filterIsInstance<Part.Text>().single().text

    private fun draft(m: FakeModel, ev: List<RunEvent>, end: TaskState = unreviewed, outcome: RunOutcome = RunOutcome.Done,
        current: AppKnowledgeDraft? = null, goal: String = "查看闲鱼") = runBlocking {
        AppKnowledgeDrafter(m).draft(AppRunInput("r1", goal, end, outcome, ev, knownTools()), app, store.builtIn(app.name), current, "d1", 5)
    }

    /** 带读屏的一次运行:点了名为 [names] 的几个元素,第 [typeAt] 步输入 [typed]。 */
    private fun tapped(names: List<String>, typed: String? = null): List<RunEvent> {
        val elements = names.mapIndexed { i, n -> """{"ref":"@e${i + 1}","role":"button","name":"$n"}""" }.joinToString(",")
        val out = mutableListOf<RunEvent>(
            RunEvent.ModelReply(1, 1, "", "phone_launch_app", """{"package":"com.taobao.idlefish"}""", null, null, null),
            RunEvent.ToolResult(2, 1, "phone_launch_app", """{"ok":true}""", true, null),
            RunEvent.ModelReply(3, 2, "", "phone_list_elements", "{}", null, null, null),
            RunEvent.ToolResult(4, 2, "phone_list_elements", """{"ok":true,"currentApp":"com.taobao.idlefish","elements":[$elements]}""", false, null),
        )
        names.forEachIndexed { i, _ ->
            out += RunEvent.ModelReply(10L + i, 3 + i, "", "phone_tap", """{"ref":"@e${i + 1}"}""", null, null, null)
            out += RunEvent.ToolResult(30L + i, 3 + i, "phone_tap", """{"ok":true}""", true, null)
        }
        if (typed != null) {
            out += RunEvent.ModelReply(60, 20, "", "phone_type_text", """{"text":"$typed"}""", null, null, null)
            out += RunEvent.ToolResult(61, 20, "phone_type_text", """{"ok":true}""", true, null)
        }
        return out
    }

    @Test fun doneRunAddsFactsWithExistingKnowledgeInRequest() {
        val m = model(facts)
        val d = draft(m, events())!!
        assertEquals(AppKnowledgeStage.Draft, d.stage)
        assertEquals("xianyu", d.appName)
        assertEquals(facts, d.markdown)
        assertFalse(d.lesson)
        assertNull(d.revises)
        val req = request(m)
        assertTrue(req, req.contains("聊一聊")) // 内置说明的正文
        assertTrue(req, req.contains("phone_tap"))
    }

    @Test fun nothingWorthAddingYieldsNothing() {
        assertNull(draft(model("NONE"), events()))
        assertNull(draft(model("  "), events()))
    }

    @Test fun repliesThatAreNotAFactListAreRejected() {
        assertNull(draft(model("---\nname: xianyu\n---\n## 是什么\n闲鱼。"), events()))
        assertNull(draft(model("- 搜索页里点 `phone_fly` 能飞。"), events()))
        assertNull(draft(model((1..20).joinToString("\n") { "- 第 $it 条事实写在这里。" }), events()))
    }

    @Test fun revisionOfCurrentSupplement() {
        val current = AppKnowledgeDraft("d0", "xianyu", "com.taobao.idlefish", "- 旧的一条。", "r0", AppKnowledgeStage.Trial, 1)
        val m = model(facts)
        val d = draft(m, events(), current = current)!!
        assertEquals("d0", d.revises)
        assertTrue(request(m).contains("旧的一条"))
        // 改完和原来一样:不算修订
        assertNull(draft(model("- 旧的一条。"), events(), current = current))
    }

    @Test fun runSpecificsDoNotBecomeFacts() {
        val ev = tapped(listOf("消息", "妈妈", "聊一聊", "发送", "我的"), typed = "今晚回家吃饭吗")
        // 目标里点名的联系人:那一条去掉;内置说明里有的界面名字(「聊一聊」)照留
        val d = draft(model("- 消息页列表里点联系人「妈妈」进入会话。\n- 商品页底部的「聊一聊」进入和卖家的会话。"), ev, goal = "在闲鱼上给妈妈发消息")!!
        assertEquals("- 商品页底部的「聊一聊」进入和卖家的会话。", d.markdown)
        // 照抄了输入的文字:整份丢掉
        assertNull(draft(model("- 会话里输入今晚回家吃饭吗后点「发送」。"), ev, goal = "在闲鱼上给妈妈发消息"))
        // 一整句照抄回答
        val answered = TaskState.Succeeded("已经发给妈妈了,她的头像是一只橘猫", verified = false, reviewed = false)
        assertNull(draft(model("- 会话列表里显示她的头像是一只橘猫。"), ev, answered, goal = "在闲鱼上给妈妈发消息"))
    }

    /** 手机上读到一屏:底部一排标签、中间列表里一列会话;点了 [taps](按名字)。 */
    private fun listed(taps: List<String>): List<RunEvent> {
        val tabs = listOf("消息", "通讯录", "我的").mapIndexed { i, n -> Triple(n, "TextView", listOf(i * 333, 920, i * 333 + 333, 999)) }
        val rows = listOf("张三", "李四", "聊一聊", "王五").mapIndexed { i, n -> Triple(n, "LinearLayout", listOf(0, 120 + i * 100, 999, 210 + i * 100)) }
        val list = Triple("", "RecyclerView", listOf(0, 100, 999, 900))
        val all = tabs + list + rows
        val json = all.mapIndexed { i, (n, role, b) ->
            """{"ref":"@e${i + 1}","role":"$role","name":"$n","center":{"x":${(b[0] + b[2]) / 2},"y":${(b[1] + b[3]) / 2}},"bounds":[${b.joinToString(",")}]}"""
        }.joinToString(",")
        val out = mutableListOf<RunEvent>(
            RunEvent.ModelReply(1, 1, "", "phone_launch_app", """{"package":"com.taobao.idlefish"}""", null, null, null),
            RunEvent.ToolResult(2, 1, "phone_launch_app", """{"ok":true}""", true, null),
            RunEvent.ModelReply(3, 2, "", "phone_list_elements", "{}", null, null, null),
            RunEvent.ToolResult(4, 2, "phone_list_elements", """{"ok":true,"currentApp":"com.taobao.idlefish","elements":[$json]}""", false, null),
        )
        taps.forEachIndexed { i, t ->
            val ref = "@e" + (all.indexOfFirst { it.first == t } + 1)
            out += RunEvent.ModelReply(10L + i, 3 + i, "", "phone_tap", """{"ref":"$ref"}""", null, null, null)
            out += RunEvent.ToolResult(30L + i, 3 + i, "phone_tap", """{"ok":true}""", true, null)
        }
        return out
    }

    @Test fun listItemsTappedAreContentButTabsAreLabels() {
        val ev = listed(listOf("通讯录", "消息", "张三", "聊一聊"))
        // 列表里点的「张三」目标没点名,也是这一次的具体内容;底部的标签和内置说明里有的「聊一聊」照留
        val d = draft(model("- 底部的「通讯录」标签里按字母排着联系人。\n- 「消息」列表里点「张三」进入会话。\n- 会话列表里也有「聊一聊」。"),
            ev, goal = "回复最新的消息")!!
        assertEquals("- 底部的「通讯录」标签里按字母排着联系人。\n- 会话列表里也有「聊一聊」。", d.markdown)
        // 没加引号照抄名字也换掉
        assertEquals("- 「我的」页里有设置。", draft(model("- 张三的会话在列表第一行。\n- 「我的」页里有设置。"), ev, goal = "回复最新的消息")!!.markdown)
    }

    @Test fun labelsQuotedInTheAnswerStayLearnable() {
        val zh = TaskState.Succeeded("在「我发布的」里找到了", verified = false, reviewed = false)
        assertEquals("- 「我的」页里有「我发布的」入口。", draft(model("- 「我的」页里有「我发布的」入口。"), events(), zh)!!.markdown)
        val en = TaskState.Succeeded("Found it under Saved in the profile tab.", verified = false, reviewed = false)
        val fact = "- The profile tab has a Saved section listing bookmarked items."
        assertEquals(fact, draft(model(fact), events(), en, goal = "Find my saved items")!!.markdown)
        // 一整句照抄回答照样不收
        assertNull(draft(model("- Found it under Saved in the profile tab."), events(), en, goal = "Find my saved items"))
        assertNull(draft(model("- 「我的」页:在「我发布的」里找到了。"), events(), zh))
    }

    @Test fun failureReasonIsSentAsIs() {
        val bad = """{"ok":false,"error":"obscured"}"""
        val m = model("NONE")
        draft(m, events("""{"ok":true}""", bad, bad), TaskState.Failed("找不到「我发布的」入口", FailKind.GaveUp), RunOutcome.Unknown)
        assertTrue(request(m), request(m).contains("找不到「我发布的」入口"))
        assertTrue(request(m).contains("not_done"))
    }

    @Test fun ruleWordingIsNotAFact() {
        val rules = listOf(
            "- 应该先点「我的」再找设置。", "- 不要点底部的「外卖」标签。", "- You should open the search tab first.",
            "- 先点「我的」再点「设置」。", "- 建议从搜索进入。", "- 最好从「消息」页进。", "- 记得先切到「全部」。",
            "- 避免点首页的广告。", "- 别点底部的「外卖」。", "- 尽量用搜索。", "- 发布时必须选分类。", "- 请勿反复点「发布」。",
            "- Never tap the banner.", "- Always open Settings first.", "- Avoid the ads tab.", "- Make sure the list has loaded.",
            "- Try to use search.", "- Don't tap the banner.", "- DON’T use the feed.",
        )
        for (r in rules) assertTrue(r, AppKnowledgeDrafter.ruleLike(r))
        val facts = listOf(
            "- 订单页的「不要了」按钮取消订单。", "- 首页顶部是搜索框。", "- 必须先登录才能看私信。",
            "- 提交后弹出「请勿重复提交」的提示。", "- 「必须填写」的项标着红星。",
            "- The Saved tab lists bookmarked items.", "- The banner shows \"Always on sale\".",
        )
        for (f in facts) assertFalse(f, AppKnowledgeDrafter.ruleLike(f))
        val d = draft(model("- 必须先登录才能看私信。\n- 私信列表在「消息」标签里。\n- 建议从「消息」标签进。"), events())!!
        assertEquals("- 必须先登录才能看私信。\n- 私信列表在「消息」标签里。", d.markdown)
    }

    @Test fun verifiedNotesAndAPlainDoneRunAskNothing() {
        val verified = AppKnowledgeDraft("d0", "xianyu", "com.taobao.idlefish", "- 旧的一条。", "r0", AppKnowledgeStage.Verified, 1)
        val m = model(facts)
        assertNull(draft(m, events(), current = verified))
        assertTrue(m.requests.isEmpty())
        // 磕绊的照样请
        val bad = """{"ok":false,"error":"obscured"}"""
        assertTrue(draft(model(facts), events("""{"ok":true}""", bad, bad), current = verified) != null)
    }

    @Test fun olderFullAppMdSupplementCanBeRevised() {
        val old = AppKnowledgeDraft("d0", "xianyu", "com.taobao.idlefish", "---\nname: xianyu\n---\n## 界面\n首页顶部有搜索入口。", "r0", AppKnowledgeStage.Trial, 1)
        assertEquals("- 首页顶部有搜索入口。", AppKnowledgeDrafter.facts(old.markdown))
        val m = model("- 首页顶部有搜索入口。\n- 「我的」页里有「我发布的」。")
        assertEquals("d0", draft(m, events(), current = old)!!.revises)
        assertTrue(request(m).contains("- 首页顶部有搜索入口。"))
    }

    @Test fun revisionOnTrialShadowsThePreviousOne() {
        val v1 = AppKnowledgeDraft("v1", "xianyu", "p", "- a", "r", AppKnowledgeStage.Verified, 1)
        val v2 = AppKnowledgeDraft("v2", "xianyu", "p", "- b", "r", AppKnowledgeStage.Trial, 2, revises = "v1")
        assertEquals("v2", AppKnowledgeTrial.inUse(listOf(v1, v2), "xianyu")?.id)
        assertEquals("v1", AppKnowledgeTrial.inUse(listOf(v1, v2.copy(stage = AppKnowledgeStage.Archived)), "xianyu")?.id)
        assertEquals("v1", AppKnowledgeTrial.inUse(listOf(v1, v2.copy(stage = AppKnowledgeStage.Draft)), "xianyu")?.id)
    }

    @Test fun failedAndBumpyRunsLearnLessons() {
        val bad = """{"ok":false,"error":"obscured"}"""
        val ok = """{"ok":true}"""
        // 没办成:放弃或步数用完的,动过几步就学
        val gaveUp = model(facts)
        val lesson = draft(gaveUp, events(ok, bad, ok), TaskState.Failed("找不到「我发布的」入口", FailKind.GaveUp), RunOutcome.Unknown)!!
        assertTrue(lesson.lesson)
        val req = request(gaveUp)
        assertTrue(req, req.contains("没办成") && req.contains("obscured"))
        assertTrue(draft(model(facts), events(ok, ok, ok), TaskState.Succeeded("只看了一部分", verified = false, reviewed = false), RunOutcome.Partial)!!.lesson)
        // 办成了但磕绊:动作不够数也学
        val bumpy = draft(model(facts), events(ok, bad, bad))!!
        assertTrue(bumpy.lesson)
        assertTrue(draft(model(facts), events(ok, ok, ok, ok, ok, ok, ok), unreviewed)!!.lesson) // 返回了两次
    }

    @Test fun runsThatTeachNothingAboutTheAppAskNoModel() {
        val ok = """{"ok":true}"""
        val cases = listOf(
            Triple(events(), TaskState.Cancelled as TaskState, RunOutcome.Unknown),
            Triple(events(), unreviewed, RunOutcome.Unknown),
            Triple(events(), TaskState.Succeeded("ok", verified = false, note = "x"), RunOutcome.Done), // 核查跑过没通过
            Triple(events(), TaskState.Failed("模型出错", FailKind.Model), RunOutcome.Unknown),
            Triple(events(ok, ok, ok), unreviewed, RunOutcome.Done), // 顺利办成、动作太少
            Triple(events(ok, ok), TaskState.Failed("x", FailKind.GaveUp), RunOutcome.Unknown), // 一两步就停下
            Triple(events(extra = listOf(RunEvent.Takeover(50, "login", "skipped", 10, 0))), unreviewed, RunOutcome.Done),
            Triple(events(extra = listOf(RunEvent.Takeover(50, "payment", "user_finished", 10, 4))), unreviewed, RunOutcome.Done),
            Triple(events(extra = listOf(RunEvent.Replay(50, "r", 1, 3, true, ""))), unreviewed, RunOutcome.Done),
        )
        for ((ev, end, outcome) in cases) {
            val m = model(facts)
            assertNull("$end $outcome", draft(m, ev, end, outcome))
            assertTrue("$end $outcome", m.requests.isEmpty())
        }
    }

    @Test fun touchedAppsAreThoseActedIn() {
        val ev = events() + listOf(
            RunEvent.ModelReply(80, 9, "", "phone_launch_app", """{"package":"com.tencent.mm"}""", null, null, null),
            RunEvent.ToolResult(81, 9, "phone_launch_app", """{"ok":true}""", true, null),
            RunEvent.ModelReply(82, 10, "", "phone_launch_app", """{"package":"com.unknown.app"}""", null, null, null),
            RunEvent.ToolResult(83, 10, "phone_launch_app", """{"ok":true}""", true, null),
        )
        assertEquals(listOf("xianyu", "wechat"), AppKnowledgeDrafter.touched(ev, knownTools(), store).map { it.name })
    }

    @Test fun lessonsNeedTwoBadTrialsInARow() {
        val done = AppKnowledgeDraft("d1", "xianyu", "p", facts, "r", AppKnowledgeStage.Trial, 1)
        assertEquals(AppKnowledgeStage.Verified, AppKnowledgeTrial.next(done, good = true).stage)
        assertEquals(AppKnowledgeStage.Archived, AppKnowledgeTrial.next(done, good = false).stage)
        val lesson = done.copy(lesson = true)
        val once = AppKnowledgeTrial.next(lesson, good = false)
        assertEquals(AppKnowledgeStage.Trial, once.stage)
        assertEquals(AppKnowledgeStage.Archived, AppKnowledgeTrial.next(once, good = false).stage)
        assertEquals(AppKnowledgeStage.Verified, AppKnowledgeTrial.next(once, good = true).stage)
    }
}
