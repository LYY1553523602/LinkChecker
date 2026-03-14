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
                else -> {}
            }
        } finally {
            rootNode.recycle()
        }
    }

    override fun onInterrupt() {
        // 服务中断
    }

    // 解析抖音点赞数
    private fun parseDouyinLikes(rootNode: AccessibilityNodeInfo) {
        // 抖音点赞按钮可能有多种标识
        val likeNodes = findNodesByTexts(rootNode, listOf("赞", "点赞", "喜欢", "likes"))
        
        for (node in likeNodes) {
            val likeText = getLikeCountFromNode(node)
            if (likeText != null) {
                val number = parseLikeNumber(likeText)
                if (number >= 0) {
                    lastResult = LikeResult(
                        likes = likeText,
                        likesNumber = number,
                        isAboveThreshold = number >= 50
                    )
                    isWaitingForResult = false
                    sendResultBroadcast()
                    return
                }
            }
        }

        // 通过View ID查找（需要适配不同版本）
        val idPatterns = listOf(
            "com.ss.android.ugc.aweme:id/title",
            "com.ss.android.ugc.aweme:id/count",
            "com.ss.android.ugc.aweme:id/like_count"
        )
        
        for (pattern in idPatterns) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(pattern)
            for (node in nodes) {
                val text = node.text?.toString()
                if (!text.isNullOrEmpty()) {
                    val number = parseLikeNumber(text)
                    if (number >= 0) {
                        lastResult = LikeResult(
                            likes = text,
                            likesNumber = number,
                            isAboveThreshold = number >= 50
                        )
                        isWaitingForResult = false
                        sendResultBroadcast()
                        return
                    }
                }
            }
        }
    }

    // 解析小红书点赞数
    private fun parseXiaohongshuLikes(rootNode: AccessibilityNodeInfo) {
        val likeNodes = findNodesByTexts(rootNode, listOf("赞", "likes", "喜欢"))
        
        for (node in likeNodes) {
            val likeText = getLikeCountFromNode(node)
            if (likeText != null) {
                val number = parseLikeNumber(likeText)
                if (number >= 0) {
                    lastResult = LikeResult(
                        likes = likeText,
                        likesNumber = number,
                        isAboveThreshold = number >= 50
                    )
                    isWaitingForResult = false
                    sendResultBroadcast()
                    return
                }
            }
        }

        // 小红书可能有特定的View ID
        val idPatterns = listOf(
            "com.xingin.xhs:id/like_count",
            "com.xingin.xhs:id/count",
            "com.xingin.xhs:id/tv_like"
        )
        
        for (pattern in idPatterns) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(pattern)
            for (node in nodes) {
                val text = node.text?.toString()
                if (!text.isNullOrEmpty()) {
                    val number = parseLikeNumber(text)
                    if (number >= 0) {
                        lastResult = LikeResult(
                            likes = text,
                            likesNumber = number,
                            isAboveThreshold = number >= 50
                        )
                        isWaitingForResult = false
                        sendResultBroadcast()
                        return
                    }
                }
            }
        }
    }

    // 解析今日头条点赞数
    private fun parseToutiaoLikes(rootNode: AccessibilityNodeInfo) {
        val likeNodes = findNodesByTexts(rootNode, listOf("赞", "点赞", "likes"))
        
        for (node in likeNodes) {
            val likeText = getLikeCountFromNode(node)
            if (likeText != null) {
                val number = parseLikeNumber(likeText)
                if (number >= 0) {
                    lastResult = LikeResult(
                        likes = likeText,
                        likesNumber = number,
                        isAboveThreshold = number >= 50
                    )
                    isWaitingForResult = false
                    sendResultBroadcast()
                    return
                }
            }
        }
    }

    // 解析微信文章点赞数
    private fun parseWechatLikes(rootNode: AccessibilityNodeInfo) {
        // 微信文章在浏览器中打开，需要找页面中的赞数字
        val likeNodes = findNodesByTexts(rootNode, listOf("赞", "点赞", "likes", "Like"))
        
        for (node in likeNodes) {
            val likeText = getLikeCountFromNode(node)
            if (likeText != null) {
                val number = parseLikeNumber(likeText)
                if (number >= 0) {
                    lastResult = LikeResult(
                        likes = likeText,
                        likesNumber = number,
                        isAboveThreshold = number >= 50
                    )
                    isWaitingForResult = false
                    sendResultBroadcast()
                    return
                }
            }
        }
        
        // 微信文章可能有特定的View ID
        val nodes = rootNode.findAccessibilityNodeInfosByViewId("com.tencent.mm:id/like_num")
        for (node in nodes) {
            val text = node.text?.toString()
            if (!text.isNullOrEmpty()) {
                val number = parseLikeNumber(text)
                if (number >= 0) {
                    lastResult = LikeResult(
                        likes = text,
                        likesNumber = number,
                        isAboveThreshold = number >= 50
                    )
                    isWaitingForResult = false
                    sendResultBroadcast()
                    return
                }
            }
        }
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
                        sibling.recycle()
                        return siblingText
                    }
                    sibling.recycle()
                }
            }
            parent.recycle()
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
            when {
                text.contains("w", ignoreCase = true) || text.contains("万", ignoreCase = true) -> {
                    // 处理万单位
                    val numStr = text.replace(Regex("[^0-9.]"), "")
                    val num = numStr.toFloatOrNull() ?: return -1
                    (num * 10000).toInt()
                }
                text.contains("k", ignoreCase = true) -> {
                    // 处理k单位
                    val numStr = text.replace(Regex("[^0-9.]"), "")
                    val num = numStr.toFloatOrNull() ?: return -1
                    (num * 1000).toInt()
                }
                else -> {
                    // 普通数字
                    val numStr = text.replace(Regex("[^0-9]"), "")
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
