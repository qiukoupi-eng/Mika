package com.wangzan101.mika.core.uistate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenContentTest {
    private fun el(ref: String, role: String, name: String, vararg b: Int, scrollable: Boolean = false) =
        UiElement(ref, role, name, 0, 0, b.toList(), null, clickable = true, editable = false, scrollable = scrollable)

    @Test fun listRowsAreContentAndFixedFrameIsNot() {
        val title = el("@e1", "TextView", "消息", 0, 0, 999, 80)
        val tabs = listOf("消息", "通讯录", "我的").mapIndexed { i, n -> el("@t$i", "TextView", n, i * 333, 920, i * 333 + 333, 999) }
        // 没标成列表的一列同类条目:左右对齐、上下排开三个起
        val rows = listOf("张三", "李四", "王五").mapIndexed { i, n -> el("@r$i", "ViewGroup", n, 0, 100 + i * 100, 999, 190 + i * 100) }
        val all = listOf(title) + tabs + rows
        assertTrue(rows.all { ScreenContent.content(it, all) })
        assertFalse(ScreenContent.content(title, all))
        assertFalse(tabs.any { ScreenContent.content(it, all) })
        // 两个上下摆的按钮不算一列条目
        val two = listOf(el("@b1", "Button", "登录", 100, 500, 900, 560), el("@b2", "Button", "注册", 100, 600, 900, 660))
        assertFalse(two.any { ScreenContent.content(it, two) })
    }

    @Test fun anythingInsideAListOrScrollAreaIsContent() {
        val box = el("@e1", "RecyclerView", "", 0, 100, 999, 900)
        val item = el("@e2", "TextView", "张三", 0, 120, 500, 200)
        assertTrue(ScreenContent.content(item, listOf(box, item)))
        val scroll = el("@e1", "FrameLayout", "", 0, 100, 999, 900, scrollable = true)
        assertTrue(ScreenContent.content(item, listOf(scroll, item)))
        assertFalse(ScreenContent.content(item, listOf(item)))
    }
}
