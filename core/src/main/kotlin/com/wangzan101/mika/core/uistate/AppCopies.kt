package com.wangzan101.mika.core.uistate

/**
 * 装在另一份用户(分身、工作资料一类的资料)里的那份 App:[packageName] 包名,[userId] 那份用户的编号(打开时用它指定),
 * [userName] 系统给那份用户起的名字(随系统语言,不写死;读不到为空)。
 */
data class AppCopy(val packageName: String, val userId: Int, val userName: String) {
    /** 列给模型看的名字:「App 名 (another copy: 那份用户的名字)」。 */
    fun label(appLabel: String): String = "$appLabel (another copy: ${userName.ifBlank { "user $userId" }})"
}

/**
 * 哪几份别的用户里的 App 能给 Mika 打开,以及列出来的那几项。纯逻辑,内置助手(判哪几份用户可用)和 Mika(列出、核对)共用。
 *
 * 判一份用户能不能用(按安卓原版的多用户概念,读 UserManager 给的 UserInfo、UserProperties,不看名字):
 * 1. 不是当前用户自己;正在运行;没处于安静模式(工作资料暂停、私密空间锁着时系统把它置为安静模式);
 *    不带访客、已停用、临时、演示这几个标记。
 * 2. 不是私密一类的资料:用户类型里带 private 的、或系统标明对别的 App 隐藏的(UserProperties 的 profileApiVisibility),一律不列也不打开,
 *    哪怕它正在运行、和当前用户在同一组里。
 * 3. 系统把它当作当前用户的资料:profileGroupId 和当前用户的那一组相同(不在任何组里的另一个完整用户、访客都不算)。
 * 4. 而且看得出是分身或工作资料:用户类型带 clone(原版的分身类型 android.os.usertype.profile.CLONE),
 *    或是原版的工作资料类型 android.os.usertype.profile.MANAGED,或 UserProperties 说它和当前用户共用媒体(原版分身的特征)。
 * 任何一项读不到都按不满足算:宁可漏列一份,不碰用户没打算给别人看的资料。
 */
object AppCopies {
    // UserInfo.FLAG_*(原版的值)
    const val FLAG_GUEST = 0x4
    const val FLAG_DISABLED = 0x40
    const val FLAG_QUIET_MODE = 0x80
    const val FLAG_EPHEMERAL = 0x100
    const val FLAG_DEMO = 0x200
    private const val EXCLUDED_FLAGS = FLAG_GUEST or FLAG_DISABLED or FLAG_QUIET_MODE or FLAG_EPHEMERAL or FLAG_DEMO

    /** UserInfo.NO_PROFILE_GROUP_ID(即 UserHandle.USER_NULL):不在任何资料组里。 */
    const val NO_PROFILE_GROUP = -10000
    const val TYPE_MANAGED = "android.os.usertype.profile.MANAGED"

    /**
     * 内置助手读到的一份用户:[flags] UserInfo 的标记,[type] 用户类型(读不到为 null),[profileGroupId] 资料组,
     * [running] 正在运行,[quiet] 处于安静模式,[mediaShared] UserProperties.isMediaSharedWithParent,
     * [hidden] UserProperties 标明对别的 App 隐藏;这两项读不到为 null。
     */
    data class User(
        val id: Int,
        val flags: Int,
        val type: String?,
        val profileGroupId: Int,
        val running: Boolean,
        val quiet: Boolean = false,
        val mediaShared: Boolean? = null,
        val hidden: Boolean? = null,
    )

    /** 一份用户为什么能用或不能用;只有 [Copy] 能用。 */
    enum class Verdict { Copy, Self, Stopped, Quiet, Flags, Private, NotLinked, NotCopy }

    fun verdict(u: User, current: Int, group: Int): Verdict {
        val type = u.type.orEmpty()
        return when {
            u.id == current -> Verdict.Self
            !u.running -> Verdict.Stopped
            u.quiet || u.flags and FLAG_QUIET_MODE != 0 -> Verdict.Quiet
            u.flags and EXCLUDED_FLAGS != 0 -> Verdict.Flags
            type.contains("private", ignoreCase = true) || u.hidden == true -> Verdict.Private
            group == NO_PROFILE_GROUP || u.profileGroupId != group -> Verdict.NotLinked
            type.contains("clone", ignoreCase = true) || type == TYPE_MANAGED || u.mediaShared == true -> Verdict.Copy
            else -> Verdict.NotCopy
        }
    }

    /** 当前用户 [current] 所在的资料组;当前用户读不到或不在组里时就是它自己。 */
    fun groupOf(current: Int, users: List<User>): Int =
        users.firstOrNull { it.id == current }?.profileGroupId?.takeIf { it != NO_PROFILE_GROUP } ?: current

    /** 能打开里面 App 的那几份别的用户的编号。 */
    fun usable(current: Int, users: List<User>): List<Int> {
        val group = groupOf(current, users)
        return users.filter { verdict(it, current, group) == Verdict.Copy }.map { it.id }
    }

    /** 给诊断信息的一行:每份别的用户的编号、标记、类型、资料组、判定;不含名字和装了什么。 */
    fun summary(current: Int, users: List<User>): String {
        val group = groupOf(current, users)
        val others = users.filter { it.id != current }
        val usable = others.count { verdict(it, current, group) == Verdict.Copy }
        if (others.isEmpty()) return "no other users"
        return "${others.size} other, $usable usable: " + others.joinToString("; ") { u ->
            "${u.id} flags=0x${Integer.toHexString(u.flags)} type=${u.type ?: "?"} group=${u.profileGroupId}" +
                (if (u.running) " running" else " stopped") + " -> ${verdict(u, current, group).name.lowercase()}"
        }
    }

    /**
     * 列出的另一份:[apps] 当前用户能打开的 App(包名, 显示名),[users] 能用的那几份用户(带名字),
     * [packages] 每份用户里装的包。只列当前用户也装着的(和列表里的 App 对得上),按 [apps] 的先后排。
     */
    fun copies(apps: List<Pair<String, String>>, users: List<UserProfile>, packages: Map<Int, Set<String>>): List<AppCopy> =
        apps.flatMap { (pkg, _) ->
            users.filter { pkg in packages[it.id].orEmpty() }.map { AppCopy(pkg, it.id, it.name) }
        }
}
