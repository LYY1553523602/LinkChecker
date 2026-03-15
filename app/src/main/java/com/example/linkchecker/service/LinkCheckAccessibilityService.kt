package com.example.linkchecker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.linkchecker.model.Platform
import java.util.regex.Pattern

class LinkCheckAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: LinkCheckAccessibilityService? = null
        
        @Volatile
        var isWaitingForResult = false
        
        @Volatile
        var currentPlatform: Platform? = null
        
        @Volatile
        var lastResult: LikeResult? = null
        
        fun reset() {
            isWaitingForResult = false
            currentPlatform = null
            lastResult = null
        }
    }

    data class LikeResult(
        val likes: String,
        val likesNumber: Int,
        val isAboveThreshold: Boolean
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isWaitingForResult || currentPlatform == null) return

        val rootNode = rootInActiveWindow ?: return
        
        try {
            when (currentPlatform) {
                Platform.DOUYIN -> parseDouyinLikes(rootNode)
                Platform.XIAOHONGSHU -> parseXiaohongshuLikes(rootNode)
                Platform.TOUTIAO -> parseToutiaoLikes(rootNode)
                Platform.WECHAT -> parseWechatLikes(rootNode)
                Platform.WEIBO -> parseWeiboLikes(rootNode)
                Platform.KUAISHOU -> parseKuaishouLikes(rootNode)
                else -> {}
            }
        } finally {
            // 注意：在某些 Android 版本上，rootNode 不需要手动 recycle，或者 recycle 后会导致后续访问出错
            // 但为了防止内存泄漏，通常建议 recycle。这里我们根据实际情况处理。
        }
    }

    override fun onInterrupt() {
        // 服务中断
    }

    // 解析抖音点赞数
    private fun parseDouyinLikes(rootNode: AccessibilityNodeInfo) {
        val idPatterns = listOf(
            "com.ss.android.ugc.aweme:id/like_count",
            "com.ss.android.ugc.aweme:id/count",
            "com.ss.android.ugc.aweme:id/title"
        )
        if (tryParseByIds(rootNode, idPatterns)) return
        
        tryParseByText(rootNode, listOf("赞", "点赞", "喜欢", "likes"))
    }

    // 解析小红书点赞数
    private fun parseXiaohongshuLikes(rootNode: AccessibilityNodeInfo) {
        val idPatterns = listOf(
            "com.xingin.xhs:id/like_count",
            "com.xingin.xhs:id/count",
            "com.xingin.xhs:id/tv_like"
        )
        if (tryParseByIds(rootNode, idPatterns)) return
        
        tryParseByText(rootNode, listOf("赞", "likes", "喜欢"))
    }

    // 解析今日头条点赞数
    private fun parseToutiaoLikes(rootNode: AccessibilityNodeInfo) {
        tryParseByText(rootNode, listOf("赞", "点赞", "likes"))
    }

    // 解析微信文章点赞数
    private fun parseWechatLikes(rootNode: AccessibilityNodeInfo) {
        val idPatterns = listOf("com.tencent.mm:id/like_num")
        if (tryParseByIds(rootNode, idPatterns)) return
        
        tryParseByText(rootNode, listOf("赞", "点赞", "likes", "Like"))
    }

    // 解析微博点赞数
    private fun parseWeiboLikes(rootNode: AccessibilityNodeInfo) {
        val idPatterns = listOf(
            "com.sina.weibo:id/tv_like_count",
            "com.sina.weibo:id/like_count"
        )
        if (tryParseByIds(rootNode, idPatterns)) return
        
        tryParseByText(rootNode, listOf("赞", "点赞", "likes"))
    }

    // 解析快手点赞数
    private fun parseKuaishouLikes(rootNode: AccessibilityNodeInfo) {
        val idPatterns = listOf(
            "com.smile.gifmaker:id/like_count",
            "com.smile.gifmaker:id/count"
        )
        if (tryParseByIds(rootNode, idPatterns)) return
        
        tryParseByText(rootNode, listOf("赞", "点赞", "likes"))
    }

    private fun tryParseByIds(rootNode: AccessibilityNodeInfo, ids: List<String>): Boolean {
        for (id in ids) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                val text = node.text?.toString()
                if (!text.isNullOrEmpty()) {
                    val number = parseLikeNumber(text)
                    if (number >= 0) {
                        handleSuccess(text, number)
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun tryParseByText(rootNode: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val likeNodes = findNodesByTexts(rootNode, keywords)
        for (node in likeNodes) {
            val likeText = getLikeCountFromNode(node)
            if (likeText != null) {
                val number = parseLikeNumber(likeText)
                if (number >= 0) {
                    handleSuccess(likeText, number)
                    return true
                }
            }
        }
        return false
    }

    private fun handleSuccess(text: String, number: Int) {
        lastResult = LikeResult(
            likes = text,
            likesNumber = number,
            isAboveThreshold = number >= 50
        )
        isWaitingForResult = false
        sendResultBroadcast()
    }

    // 根据文本查找节点
    private fun findNodesByTexts(rootNode: AccessibilityNodeInfo, texts: List<String>): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        
        fun traverse(node: AccessibilityNodeInfo) {
            val nodeText = node.text?.toString()
            if (nodeText != null) {
                for (text in texts) {
                    if (nodeText.contains(text, ignoreCase = true)) {
                        results.add(node)
                        break
                    }
                }
            }
            
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    traverse(child)
                }
            }
        }
        
        traverse(rootNode)
        return results
    }

    // 从节点获取点赞数
    private fun getLikeCountFromNode(node: AccessibilityNodeInfo): String? {
        // 获取节点自身的文本
        val text = node.text?.toString()
        if (!text.isNullOrEmpty() && containsNumber(text)) {
            return text
        }
        
        // 检查兄弟节点
        val parent = node.parent
        if (parent != null) {
            for (i in 0 until parent.childCount) {
                val sibling = parent.getChild(i)
                if (sibling != null && sibling != node) {
                    val siblingText = sibling.text?.toString()
                    if (!siblingText.isNullOrEmpty() && containsNumber(siblingText)) {
                        return siblingText
                    }
                }
            }
        }
        
        return null
    }

    // 检查文本是否包含数字
    private fun containsNumber(text: String): Boolean {
        return Pattern.compile("[0-9]").matcher(text).find()
    }

    // 解析点赞数为数字
    private fun parseLikeNumber(text: String): Int {
        return try {
            // 移除所有非数字和非小数点的字符，但保留单位标识
            val cleanText = text.lowercase()
            when {
                cleanText.contains("w") || cleanText.contains("万") -> {
                    val numStr = cleanText.replace(Regex("[^0-9.]"), "")
                    val num = numStr.toFloatOrNull() ?: return -1
                    (num * 10000).toInt()
                }
                cleanText.contains("k") -> {
                    val numStr = cleanText.replace(Regex("[^0-9.]"), "")
                    val num = numStr.toFloatOrNull() ?: return -1
                    (num * 1000).toInt()
                }
                else -> {
                    val numStr = cleanText.replace(Regex("[^0-9]"), "")
                    if (numStr.isEmpty()) -1 else numStr.toInt()
                }
            }
        } catch (e: Exception) {
            -1
        }
    }

    // 发送结果广播
    private fun sendResultBroadcast() {
        val intent = Intent("com.example.linkchecker.LIKE_RESULT")
        intent.putExtra("found", lastResult != null)
        intent.putExtra("likes", lastResult?.likes ?: "")
        intent.putExtra("likesNumber", lastResult?.likesNumber ?: -1)
        intent.putExtra("isAboveThreshold", lastResult?.isAboveThreshold ?: false)
        sendBroadcast(intent)
        
        // 返回到本应用
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // 公共方法：开始等待结果
    fun startWaiting(platform: Platform) {
        currentPlatform = platform
        isWaitingForResult = true
        lastResult = null
    }

    // 公共方法：停止等待
    fun stopWaiting() {
        isWaitingForResult = false
    }
}
