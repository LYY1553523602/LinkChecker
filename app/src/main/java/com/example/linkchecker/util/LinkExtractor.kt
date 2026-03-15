package com.example.linkchecker.util

import com.example.linkchecker.model.LinkItem
import com.example.linkchecker.model.Platform
import java.util.regex.Pattern

object LinkExtractor {

    // 抖音链接正则
    private val DOUYIN_PATTERNS = listOf(
        Pattern.compile("https?://v\\.douyin\\.com/[a-zA-Z0-9]+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("https?://www\\.iesdouyin\\.com/share/video/[0-9]+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("https?://www\\.douyin\\.com/video/[0-9]+", Pattern.CASE_INSENSITIVE)
    )

    // 小红书链接正则
    private val XIAOHONGSHU_PATTERNS = listOf(
        Pattern.compile("https?://www\\.xiaohongshu\\.com/explore/[a-zA-Z0-9]+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("https?://xhslink\\.com/[a-zA-Z0-9]+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("http://xhslink\\.com/[a-zA-Z0-9]+", Pattern.CASE_INSENSITIVE)
    )

    // 今日头条链接正则
    private val TOUTIAO_PATTERNS = listOf(
        Pattern.compile("https?://www\\.toutiao\\.com/article/[0-9]+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("https?://m\\.toutiao\\.com/is/[a-zA-Z0-9]+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("https?://toutiao\\.com/group/[0-9]+", Pattern.CASE_INSENSITIVE)
    )

    // 微信文章链接正则
    private val WECHAT_PATTERNS = listOf(
        Pattern.compile("https?://mp\\.weixin\\.qq\\.com/s/[a-zA-Z0-9_-]+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("https?://mp\\.weixin\\.qq\\.com/s\\?[^\\s]+", Pattern.CASE_INSENSITIVE)
    )

    // 通用URL正则
    private val URL_PATTERN = Pattern.compile(
        "https?://[a-zA-Z0-9][-a-zA-Z0-9]*\\.[-a-zA-Z0-9]+(?:/[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|])?",
        Pattern.CASE_INSENSITIVE
    )

    fun extractLinks(text: String): List<LinkItem> {
        val links = mutableListOf<LinkItem>()
        val foundUrls = mutableSetOf<String>()

        // 先匹配所有URL
        val matcher = URL_PATTERN.matcher(text)
        while (matcher.find()) {
            val url = matcher.group()
            if (foundUrls.add(url)) {
                val platform = detectPlatform(url)
                links.add(LinkItem(url = url, platform = platform))
            }
        }

        return links
    }

    private fun detectPlatform(url: String): Platform {
        // 检查抖音
        for (pattern in DOUYIN_PATTERNS) {
            if (pattern.matcher(url).find()) {
                return Platform.DOUYIN
            }
        }

        // 检查小红书
        for (pattern in XIAOHONGSHU_PATTERNS) {
            if (pattern.matcher(url).find()) {
                return Platform.XIAOHONGSHU
            }
        }

        // 检查今日头条
        for (pattern in TOUTIAO_PATTERNS) {
            if (pattern.matcher(url).find()) {
                return Platform.TOUTIAO
            }
        }

        // 检查微信
        for (pattern in WECHAT_PATTERNS) {
            if (pattern.matcher(url).find()) {
                return Platform.WECHAT
            }
        }

        // 检查域名关键字
        return when {
            url.contains("douyin", ignoreCase = true) -> Platform.DOUYIN
            url.contains("xiaohongshu", ignoreCase = true) || 
            url.contains("xhslink", ignoreCase = true) -> Platform.XIAOHONGSHU
            url.contains("toutiao", ignoreCase = true) -> Platform.TOUTIAO
            url.contains("weixin.qq", ignoreCase = true) -> Platform.WECHAT
            else -> Platform.UNKNOWN
        }
    }

    fun getPlatformName(platform: Platform): String {
        return when (platform) {
            Platform.DOUYIN -> "抖音"
            Platform.XIAOHONGSHU -> "小红书"
            Platform.TOUTIAO -> "今日头条"
            Platform.WECHAT -> "微信"
            Platform.UNKNOWN -> "未知"
        }
    }
}
