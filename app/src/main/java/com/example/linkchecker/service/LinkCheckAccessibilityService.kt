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
        var lastResult: FullResult? = null
        
        fun reset() {
            isWaitingForResult = false
            currentPlatform = null
            lastResult = null
        }
    }

    data class FullResult(
        val title: String? = null,
        val author: String? = null,
        val fans: String? = null,
        val likes: String? = null,
        val likesNumber: Int = -1,
        val comments: String? = null,
        val shares: String? = null,
        val isAboveThreshold: Boolean = false,
        val isInvalid: Boolean = false
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return
        
        // 1. 自动点击“同意/打开”弹窗
        handleAutoClickDialogs(rootNode)

        if (!isWaitingForResult || currentPlatform == null) return

        try {
            // 2. 检查链接是否失效
            if (checkIsInvalid(rootNode)) {
                handleInvalid()
                return
            }

            // 3. 根据平台抓取数据
            when (currentPlatform) {
                Platform.DOUYIN -> parseDouyin(rootNode)
                Platform.XIAOHONGSHU -> parseXiaohongshu(rootNode)
                Platform.TOUTIAO -> parseToutiao(rootNode)
                Platform.WECHAT -> parseWechat(rootNode)
                Platform.WEIBO -> parseWeibo(rootNode)
                Platform.KUAISHOU -> parseKuaishou(rootNode)
                else -> {}
            }
        } catch (e: Exception) {
            // 忽略异常
        }
    }

    private fun handleAutoClickDialogs(rootNode: AccessibilityNodeInfo) {
        val keywords = listOf("允许", "同意", "打开", "继续", "确认", "Open", "Allow", "Accept")
        for (keyword in keywords) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                if (node.isClickable && node.isEnabled) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
        }
    }

    private fun checkIsInvalid(rootNode: AccessibilityNodeInfo): Boolean {
        val invalidKeywords = listOf("链接已失效", "内容已被删除", "页面不存在", "404", "Invalid Link", "Deleted")
        for (keyword in invalidKeywords) {
            if (rootNode.findAccessibilityNodeInfosByText(keyword).isNotEmpty()) {
                return true
            }
        }
        return false
    }

    private fun handleInvalid() {
        lastResult = FullResult(isInvalid = true)
        isWaitingForResult = false
        sendResultBroadcast()
    }

    // --- 平台抓取逻辑 ---

    private fun parseDouyin(rootNode: AccessibilityNodeInfo) {
        val likes = findTextById(rootNode, "com.ss.android.ugc.aweme:id/like_count")
        val comments = findTextById(rootNode, "com.ss.android.ugc.aweme:id/comment_count")
        val shares = findTextById(rootNode, "com.ss.android.ugc.aweme:id/share_count")
        
        if (likes != null) {
            val num = parseLikeNumber(likes)
            handleSuccess(null, null, null, likes, num, comments, shares)
        }
    }

    private fun parseXiaohongshu(rootNode: AccessibilityNodeInfo) {
        val likes = findTextById(rootNode, "com.xingin.xhs:id/like_count")
        val comments = findTextById(rootNode, "com.xingin.xhs:id/comment_count")
        val shares = findTextById(rootNode, "com.xingin.xhs:id/share_count")
        
        if (likes != null) {
            val num = parseLikeNumber(likes)
            handleSuccess(null, null, null, likes, num, comments, shares)
        }
    }

    private fun parseToutiao(rootNode: AccessibilityNodeInfo) {
        val likes = findTextById(rootNode, "com.ss.android.article.news:id/like_count")
        val fans = findTextById(rootNode, "com.ss.android.article.news:id/fans_count")
        
        if (likes != null) {
            val num = parseLikeNumber(likes)
            handleSuccess(null, null, fans, likes, num, null, null)
        }
    }

    private fun parseWechat(rootNode: AccessibilityNodeInfo) {
        val likes = findTextById(rootNode, "com.tencent.mm:id/like_num")
        if (likes != null) {
            val num = parseLikeNumber(likes)
            handleSuccess(null, null, null, likes, num, null, null)
        }
    }

    private fun parseWeibo(rootNode: AccessibilityNodeInfo) {
        val likes = findTextById(rootNode, "com.sina.weibo:id/tv_like_count")
        if (likes != null) {
            val num = parseLikeNumber(likes)
            handleSuccess(null, null, null, likes, num, null, null)
        }
    }

    private fun parseKuaishou(rootNode: AccessibilityNodeInfo) {
        val likes = findTextById(rootNode, "com.smile.gifmaker:id/like_count")
        if (likes != null) {
            val num = parseLikeNumber(likes)
            handleSuccess(null, null, null, likes, num, null, null)
        }
    }

    // --- 辅助方法 ---

    private fun findTextById(rootNode: AccessibilityNodeInfo, id: String): String? {
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
        return if (nodes.isNotEmpty()) nodes[0].text?.toString() else null
    }

    private fun handleSuccess(title: String?, author: String?, fans: String?, likes: String?, likesNumber: Int, comments: String?, shares: String?) {
        lastResult = FullResult(
            title = title,
            author = author,
            fans = fans,
            likes = likes,
            likesNumber = likesNumber,
            comments = comments,
            shares = shares,
            isAboveThreshold = likesNumber >= 50
        )
        isWaitingForResult = false
        sendResultBroadcast()
    }

    private fun parseLikeNumber(text: String): Int {
        return try {
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

    private fun sendResultBroadcast() {
        val intent = Intent("com.example.linkchecker.LIKE_RESULT")
        intent.putExtra("found", lastResult != null)
        intent.putExtra("fans", lastResult?.fans)
        intent.putExtra("likes", lastResult?.likes)
        intent.putExtra("likesNumber", lastResult?.likesNumber ?: -1)
        intent.putExtra("comments", lastResult?.comments)
        intent.putExtra("shares", lastResult?.shares)
        intent.putExtra("isAboveThreshold", lastResult?.isAboveThreshold ?: false)
        intent.putExtra("isInvalid", lastResult?.isInvalid ?: false)
        sendBroadcast(intent)
        
        // 自动关闭页面：连续执行两次返回操作，通常能关闭当前页面或返回到 APP 主页
        performGlobalAction(GLOBAL_ACTION_BACK)
        Thread.sleep(500)
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun startWaiting(platform: Platform) {
        currentPlatform = platform
        isWaitingForResult = true
        lastResult = null
    }

    override fun onInterrupt() {
        instance = null
    }
}
