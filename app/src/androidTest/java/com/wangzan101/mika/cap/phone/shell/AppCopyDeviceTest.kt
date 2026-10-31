package com.wangzan101.mika.cap.phone.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.core.uistate.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机:有能用的另一份用户(分身、工作资料)时,把装在那里的一个 App 开到 Mika 的屏上,最上面的任务属于那份用户,主屏不变。
 * 没有另一份用户、或那里没有和当前用户共有的 App 时跳过。只打开,不在 App 里做任何操作。
 */
@RunWith(AndroidJUnit4::class)
class AppCopyDeviceTest {
    private val agent = DeviceSupport.newAgent()

    @After
    fun tearDown() = agent.release()

    @Test
    fun launchesTheCopyInItsUserOnMikasDisplay() = runBlocking<Unit> {
        val shell = DeviceSupport.shell()
        val users = shell.appCopyUsers()?.getIntArray(ShellService.KEY_COPY_USERS)
        assumeTrue("no usable other user on this device: ${shell.appCopyUsers()?.getString(ShellService.KEY_COPY_SUMMARY)}", users != null && users.isNotEmpty())
        val copy = agent.screen.appCopies().firstOrNull()
        assumeTrue("no app shared between this user and another usable user", copy != null)
        copy!!
        val main = DeviceSupport.mainScreenState(shell)
        val r = agent.screen.openAppCopy(copy.packageName, copy.userId)
        // 系统先问打开哪一个时也算打开了;这里只认直接开出来的
        assertTrue("open ${copy.packageName} in user ${copy.userId}: $r", r is ActionResult.Ok)
        val top = shell.topTaskId(agent.display.displayId)
        assertEquals(copy.packageName, shell.packageOfTask(top))
        assertEquals(copy.userId, shell.userOfTask(top))
        assertEquals(copy.userId, agent.screen.target.value?.userId)
        assertEquals("main screen changed", main, DeviceSupport.mainScreenState(shell))
        // 不在能用之列的用户一律不启动
        assertEquals(ShellService.START_USER_NOT_ALLOWED, shell.startAppInUser(agent.display.displayId, copy.packageName, 99_999, false))
    }
}
