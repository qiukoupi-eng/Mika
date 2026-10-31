package com.wangzan101.mika.core.uistate

/**
 * 屏上哪些元素是内容(联系人、消息、商品这类列表条目,可能带个人信息),哪些是页面的固定框架(标题栏、标签、按钮)。
 * 经验路线挑锚点、自学 App 知识时防泄漏都按这一处分。
 */
object ScreenContent {
    private val LIST_ROLES = setOf("list", "grid", "recycler", "scroll")
    /** 安卓的列表、可滚动区按类名认(角色是类名的最后一段)。 */
    private val LIST_CLASSES = listOf("listview", "recyclerview", "gridview", "scrollview")
    /** 同一角色、左右对齐、上下排开的元素到这么多个,就算一列重复的条目(两个上下摆的按钮常有,三个起多半是列表)。 */
    const val MIN_REPEATS = 3

    /** 列表或可滚动区:可滚动的,或角色是列表一类(网页的 list/grid,安卓的 RecyclerView、ListView 这类)。 */
    fun container(e: UiElement): Boolean = e.scrollable || e.role.lowercase().let { r -> r in LIST_ROLES || LIST_CLASSES.any(r::endsWith) }

    fun inside(e: UiElement, c: UiElement) = e !== c && e.bounds.size == 4 && c.bounds.size == 4 &&
        e.bounds[0] >= c.bounds[0] && e.bounds[1] >= c.bounds[1] && e.bounds[2] <= c.bounds[2] && e.bounds[3] <= c.bounds[3]

    /**
     * [e] 是内容:在列表、可滚动区里,或和同角色的元素左右对齐、上下排成一列([MIN_REPEATS] 个起)。
     * 横着排的一排同类(底部标签、顶部分段)是框架,不算。
     */
    fun content(e: UiElement, all: List<UiElement>): Boolean {
        if (all.any { container(it) && inside(e, it) }) return true
        if (e.bounds.size != 4) return false
        val column = all.filter { o -> o.role == e.role && o.bounds.size == 4 && o.bounds[0] == e.bounds[0] && o.bounds[2] == e.bounds[2] }
        return column.map { it.bounds[1] }.distinct().size >= MIN_REPEATS
    }
}
