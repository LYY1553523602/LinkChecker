package com.example.linkchecker.util

import com.example.linkchecker.model.LinkItem
import com.example.linkchecker.model.Platform
import java.util.regex.Pattern

object LinkExtractor {

    private val URL_PATTERN = Pattern.compile(
        "(https?://[a-zA-Z0-9][-a-zA-Z0-9]*(\\.[a-zA-Z0-9][-a-zA-Z0-9]*)+(:[0-9]+)?(/[^\\s\\u4e00-\\u9fa5\\u3000-\\u303f\\uff00-\\uffef]*)?)",
        Pattern.CASE_INSENSITIVE
    )

    fun extract(text: String): List<LinkItem> = extractLinks(text)

    fun extractLinks(text: String): List<LinkItem> {
        val links = mutableListOf<LinkItem>()
        val lines = text.split("\n")
        
        var currentTitle: String? = null
        var currentAuthor: String? = null
        var currentPlatform: Platform = Platform.UNKNOWN

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue

            // 1. 尝试从行中提取元数据（如：微信“界面新闻”：标题内容）
            val metaMatcher = Pattern.compile("(微信|抖音|小红书|今日头条|微博|快手|西瓜视频)“([^”]+)”：(.+)").matcher(trimmedLine)
            if (metaMatcher.find()) {
                currentPlatform = parsePlatformName(metaMatcher.group(1))
                currentAuthor = metaMatcher.group(2)
                currentTitle = metaMatcher.group(3)
                continue
            }

            // 2. 尝试提取 URL
            val urlMatcher = URL_PATTERN.matcher(trimmedLine)
            if (urlMatcher.find()) {
                var url = urlMatcher.group(1) ?: ""
                url = cleanUrl(url)
                
                if (url.isNotEmpty()) {
                    val detectedPlatform = detectPlatform(url)
                    links.add(LinkItem(
                        url = url,
                        platform = if (detectedPlatform != Platform.UNKNOWN) detectedPlatform else currentPlatform,
                        title = currentTitle,
                        author = currentAuthor
                    ))
                    // 提取完一个链接后重置元数据，防止误用
                    currentTitle = null
                    currentAuthor = null
                    currentPlatform = Platform.UNKNOWN
                }
            }
        }

        return links
    }

    private fun cleanUrl(url: String): String {
        var result = url
        val trailingChars = charArrayOf('.', ',', '!', '?', ';', ':', ')', ']', '}', '>', '\"', '\'')
        while (result.isNotEmpty() && trailingChars.contains(result.last())) {
            result = result.substring(0, result.length - 1)
        }
        return result
    }

    private fun parsePlatformName(name: String): Platform {
        return when (name) {
            "抖音" -> Platform.DOUYIN
            "小红书" -> Platform.XIAOHONGSHU
            "今日头条" -> Platform.TOUTIAO
            "微信" -> Platform.WECHAT
            "微博" -> Platform.WEIBO
            "快手" -> Platform.KUAISHOU
            "西瓜视频" -> Platform.TOUTIAO // 西瓜视频通常归类为今日头条系
            else -> Platform.UNKNOWN
        }
    }

    private fun detectPlatform(url: String): Platform {
        val lowerUrl = url.lowercase()
        return when {
            lowerUrl.contains("douyin.com") || lowerUrl.contains("iesdouyin.com") -> Platform.DOUYIN
            lowerUrl.contains("xiaohongshu.com") || lowerUrl.contains("xhslink.com") -> Platform.XIAOHONGSHU
            lowerUrl.contains("toutiao.com") -> Platform.TOUTIAO
            lowerUrl.contains("mp.weixin.qq.com") || lowerUrl.contains("weixin.qq.com") -> Platform.WECHAT
            lowerUrl.contains("weibo.com") || lowerUrl.contains("weibo.cn") -> Platform.WEIBO
            lowerUrl.contains("kuaishou.com") || lowerUrl.contains("chenzhongtech.com") -> Platform.KUAISHOU
            lowerUrl.contains("ixigua.com") -> Platform.TOUTIAO
            else -> Platform.UNKNOWN
        }
    }

    fun getPlatformName(platform: Platform): String {
        return when (platform) {
            Platform.DOUYIN -> "抖音"
            Platform.XIAOHONGSHU -> "小红书"
            Platform.TOUTIAO -> "今日头条"
            Platform.WECHAT -> "微信"
            Platform.WEIBO -> "微博"
            Platform.KUAISHOU -> "快手"
            Platform.UNKNOWN -> "未知"
        }
    }
}
