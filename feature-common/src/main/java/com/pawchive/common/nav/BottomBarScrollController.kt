package com.pawchive.common.nav

/**
 * 主页面底栏滚动显隐控制接口。
 *
 * 由 `:app` 的 MainActivity 实现；主 Tab 页面（首页/搜索/收藏/下载）的列表
 * 通过 `activity as? BottomBarScrollController` 调用：
 * 列表向下滚动时底栏滑出屏幕，向上滚动（或切 Tab）时滑回。
 * 二级页面的整体显隐仍走 [AppNavigator.updateBottomNavVisibility] / 返回栈监听，互不干扰。
 */
interface BottomBarScrollController {

    /**
     * 主列表滚动回调。
     * @param dy 本次滚动的纵向位移：> 0 表示向下滚动（内容上移，隐藏底栏），
     *           < 0 表示向上滚动（内容下移，显示底栏）
     */
    fun onMainListScrolled(dy: Int)

    /** 强制显示底栏（切换内嵌 Tab、列表刷新回顶部等场景），带滑入动画 */
    fun showBottomBar()
}
