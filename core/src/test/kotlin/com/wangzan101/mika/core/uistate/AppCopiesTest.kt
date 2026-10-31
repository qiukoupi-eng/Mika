package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.AppCopies.User
import com.wangzan101.mika.core.uistate.AppCopies.Verdict
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCopiesTest {
    private val clone = "android.os.usertype.profile.CLONE"
    private val private = "android.os.usertype.profile.PRIVATE"
    private val owner = User(0, 0x4c13, "android.os.usertype.full.SYSTEM", profileGroupId = 0, running = true)

    // 一台真机上 `pm list users` 的三份用户:机主 0(4c13)、私密空间 10(2000410,没运行)、分身 128(4000410,运行中,装着同一个 App)
    private val appClones = User(128, 0x4000410, clone, profileGroupId = 0, running = true, mediaShared = true)
    private val privateSpace = User(10, 0x2000410, private, profileGroupId = 0, running = false, hidden = true)

    @Test fun theCloneProfileIsUsableAndThePrivateSpaceNeverIs() {
        assertEquals(listOf(128), AppCopies.usable(0, listOf(owner, privateSpace, appClones)))
        // 私密空间就算解锁了(在运行、不安静)、和机主同一组,也不列
        val unlocked = privateSpace.copy(running = true)
        assertEquals(listOf(128), AppCopies.usable(0, listOf(owner, unlocked, appClones)))
        assertEquals(Verdict.Private, AppCopies.verdict(unlocked, 0, 0))
        // 类型看不出是私密的、但系统标明对别的 App 隐藏的,也不列
        assertEquals(Verdict.Private, AppCopies.verdict(unlocked.copy(type = "x.vendor.SPACE", mediaShared = true), 0, 0))
        // 类型里带 private 的,系统没标隐藏也不列
        assertEquals(Verdict.Private, AppCopies.verdict(unlocked.copy(hidden = null, mediaShared = true), 0, 0))
    }

    @Test fun onlyRunningUnpausedProfilesOfTheCurrentUserCount() {
        assertEquals(Verdict.Self, AppCopies.verdict(owner, 0, 0))
        assertEquals(Verdict.Stopped, AppCopies.verdict(appClones.copy(running = false), 0, 0))
        // 暂停的工作资料(安静模式):按标记或按接口读到的都不算
        val work = User(11, 0x1030, AppCopies.TYPE_MANAGED, profileGroupId = 0, running = true)
        assertEquals(Verdict.Copy, AppCopies.verdict(work, 0, 0))
        assertEquals(Verdict.Quiet, AppCopies.verdict(work.copy(quiet = true), 0, 0))
        assertEquals(Verdict.Quiet, AppCopies.verdict(work.copy(flags = work.flags or AppCopies.FLAG_QUIET_MODE), 0, 0))
        // 访客、另一个完整用户(不在机主那一组):不列
        val guest = User(12, 0x414 or AppCopies.FLAG_GUEST, "android.os.usertype.full.GUEST", AppCopies.NO_PROFILE_GROUP, running = true)
        assertEquals(Verdict.Flags, AppCopies.verdict(guest, 0, 0))
        val secondary = User(13, 0x410, "android.os.usertype.full.SECONDARY", AppCopies.NO_PROFILE_GROUP, running = true, mediaShared = true)
        assertEquals(Verdict.NotLinked, AppCopies.verdict(secondary, 0, 0))
        // 另一个用户自己的分身(不在机主那一组)
        assertEquals(Verdict.NotLinked, AppCopies.verdict(appClones.copy(profileGroupId = 13), 0, 0))
        // 在机主那一组里,但看不出是分身或工作资料
        assertEquals(Verdict.NotCopy, AppCopies.verdict(User(14, 0x1010, "x.vendor.PROFILE", 0, running = true), 0, 0))
        // 看不出类型、但和机主共用媒体(原版分身的特征)的算
        assertEquals(Verdict.Copy, AppCopies.verdict(User(15, 0x1010, null, 0, running = true, mediaShared = true), 0, 0))
        assertEquals(emptyList<Int>(), AppCopies.usable(0, listOf(owner, guest, secondary)))
    }

    @Test fun summaryHasIdsFlagsAndVerdictsButNoNames() {
        val s = AppCopies.summary(0, listOf(owner, privateSpace, appClones))
        assertEquals(
            "2 other, 1 usable: 10 flags=0x2000410 type=$private group=0 stopped -> stopped; " +
                "128 flags=0x4000410 type=$clone group=0 running -> copy",
            s,
        )
        assertEquals("no other users", AppCopies.summary(0, listOf(owner)))
    }

    @Test fun copiesFollowTheAppListAndOnlyCountAppsTheCurrentUserHas() {
        val apps = listOf("com.example.chat" to "Chat", "com.example.notes" to "Notes")
        val users = listOf(UserProfile(128, "分身应用", true), UserProfile(11, "", true))
        val packages = mapOf(128 to setOf("com.example.chat", "com.example.only.there"), 11 to setOf("com.example.chat", "com.example.notes"))
        assertEquals(
            listOf(AppCopy("com.example.chat", 128, "分身应用"), AppCopy("com.example.chat", 11, ""), AppCopy("com.example.notes", 11, "")),
            AppCopies.copies(apps, users, packages),
        )
        assertEquals("Chat (another copy: 分身应用)", AppCopy("com.example.chat", 128, "分身应用").label("Chat"))
        assertEquals("Chat (another copy: user 11)", AppCopy("com.example.chat", 11, "").label("Chat"))
    }

    private val chatCopy = AppCopy("com.example.chat", 128, "分身应用")

    private inner class CopyScreen(private val copies: List<AppCopy> = listOf(chatCopy)) : ScreenPort by FakeScreen() {
        val opened = mutableListOf<String>()
        override suspend fun listApps() = listOf("com.example.chat" to "Chat", "com.example.notes" to "Notes")
        override suspend fun appCopies() = copies
        override suspend fun openApp(packageName: String, cold: Boolean): ActionResult { opened += packageName; return ActionResult.Ok() }
        override suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean): ActionResult {
            opened += "$packageName@$userId${if (cold) " cold" else ""}"
            return ActionResult.Ok()
        }
    }

    @Test fun listAppsAddsOneEntryPerCopyWithItsUser() = runTest {
        val r = toolDispatcher(CopyScreen()).executeRead(Part.ToolCall("a", "phone_list_apps", "{}"))
        val apps = r.content["apps"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Chat", "Chat (another copy: 分身应用)", "Notes"), apps.map { it["label"]!!.jsonPrimitive.content })
        assertEquals(listOf("com.example.chat", "com.example.chat", "com.example.notes"), apps.map { it["package"]!!.jsonPrimitive.content })
        assertEquals(128, apps[1]["user"]!!.jsonPrimitive.int)
        assertFalse("user" in apps[0])
        assertFalse("user" in apps[2])
        // packages 仍只是本来那几个
        assertEquals(listOf("com.example.chat", "com.example.notes"), r.content["packages"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun singleCopyAppsListAndLaunchAsBefore() = runTest {
        val screen = CopyScreen(copies = emptyList())
        val list = toolDispatcher(screen).executeRead(Part.ToolCall("a", "phone_list_apps", "{}"))
        assertEquals(2, list.content["apps"]!!.jsonArray.size)
        assertTrue(list.content["apps"]!!.jsonArray.none { "user" in it.jsonObject })
        val r = toolDispatcher(screen).execute(Part.ToolCall("l", "phone_launch_app", """{"package":"com.example.chat"}"""))
        assertEquals("true", r.content["ok"]!!.jsonPrimitive.content)
        assertEquals(listOf("com.example.chat"), screen.opened)
    }

    @Test fun launchWithUserOpensThatCopy() = runTest {
        val screen = CopyScreen()
        val r = toolDispatcher(screen).execute(Part.ToolCall("l", "phone_launch_app", """{"package":"com.example.chat","user":128,"cold":true}"""))
        assertEquals("true", r.content["ok"]!!.jsonPrimitive.content)
        assertEquals(listOf("com.example.chat@128 cold"), screen.opened)
    }

    @Test fun launchWithAnUnlistedUserIsRefusedWithoutLaunching() = runTest {
        val screen = CopyScreen()
        // 没列出的用户(如私密空间 10)、列出的用户里没有的 App:都不打开
        for (args in listOf("""{"package":"com.example.chat","user":10}""", """{"package":"com.example.notes","user":128}""")) {
            val r = toolDispatcher(screen).execute(Part.ToolCall("l", "phone_launch_app", args))
            assertEquals(args, "false", r.content["ok"]!!.jsonPrimitive.content)
            assertEquals(args, "copy_not_found", r.content["error"]?.jsonPrimitive?.content ?: r.content["reason"]?.jsonPrimitive?.content)
        }
        // 不是整数、超出用户编号范围:参数错
        for (args in listOf("""{"package":"com.example.chat","user":"abc"}""", """{"package":"com.example.chat","user":-1}""", """{"package":"com.example.chat","user":1.5}""")) {
            val r = toolDispatcher(screen).execute(Part.ToolCall("l", "phone_launch_app", args))
            assertEquals(args, "false", r.content["ok"]!!.jsonPrimitive.content)
        }
        assertEquals(emptyList<String>(), screen.opened)
    }
}
