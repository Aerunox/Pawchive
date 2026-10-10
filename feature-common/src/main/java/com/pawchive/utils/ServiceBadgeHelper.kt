package com.pawchive.utils

import android.content.Context
import android.content.res.Configuration
import com.pawchive.common.R
import com.google.android.material.card.MaterialCardView
import android.widget.TextView

/**
 * 平台品牌色辅助工具（PERF-018：消除 PostAdapter / CreatorAdapter 中
 * 重复的 service badge 颜色逻辑与暗色模式判断）。
 *
 * 各平台在浅色/深色模式下使用不同的颜色资源，由 [resolveBadgeColors]
 * 统一返回 (bgColorRes, textColorRes)。
 */
object ServiceBadgeHelper {

    /**
     * 根据平台名称与当前主题模式返回品牌色资源 ID 对。
     */
    fun resolveBadgeColors(service: String, isDarkMode: Boolean): Pair<Int, Int> {
        return when (service.lowercase()) {
            "patreon" -> if (isDarkMode) {
                R.color.patreon_bg_dark to R.color.patreon_text_dark
            } else {
                R.color.patreon_bg_light to R.color.patreon_text_light
            }
            "fanbox" -> if (isDarkMode) {
                R.color.fanbox_bg_dark to R.color.fanbox_text_dark
            } else {
                R.color.fanbox_bg_light to R.color.fanbox_text_light
            }
            else -> if (isDarkMode) {
                R.color.service_bg_default_dark to R.color.service_text_default_dark
            } else {
                R.color.service_bg_default_light to R.color.service_text_default_light
            }
        }
    }

    /**
     * 判断当前上下文是否处于深色模式。
     */
    fun isDarkMode(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 将解析出的颜色应用到品牌徽章 View 上。
     */
    fun applyBadgeColors(badgeCard: MaterialCardView, serviceTv: TextView, context: Context) {
        val isDark = isDarkMode(context)
        val (bgRes, textRes) = resolveBadgeColors(
            serviceTv.text.toString(), isDark
        )
        badgeCard.setCardBackgroundColor(context.getColor(bgRes))
        serviceTv.setTextColor(context.getColor(textRes))
    }
}