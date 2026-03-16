package com.example.linkchecker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.linkchecker.MainActivity
import com.example.linkchecker.model.Platform

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
        val fans: String? = null,
        val likes: String? = null,
        val likesNumber: Int = -1,
        val comments: String? = null,
        val shares: String? = null,
        val isAboveThreshold: Boolean = false,
        val isInvalid: Boolean = false
    )

    private val handler = Handler(Looper.getMainLooper())
    private var lastEventTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return
        
        // 1. 自动点击“同意/打开”弹窗 (V2.1 强化版)
        handleAutoClickDialogs(rootNode)

        if (!isWaitingForResult || currentPlatform == null) return

        // 限制处理频率，避免过度消耗资源
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEventTime < 500) return
        lastEventTime = currentTime

        try {
            // 2. 检查链接是否失效
            if (checkIsInvalid(rootNode)) {
                handleInvalid()
                return
            }

            // 3. 尝试抓取数据 (V2.1 多维容错引擎)
            val result = when (currentPlatform) {
                Platform.DOUYIN -> parseDouyin(rootNode)
                Platform.XIAOHONGSHU -> parseXiaohongshu(rootNode)
                Platform.TOUTIAO -> parseToutiao(rootNode)
                Platform.WECHAT -> parseWechat(rootNode)
                Platform.WEIBO -> parseWeibo(rootNode)
                Platform.KUAISHOU -> parseKuaishou(rootNode)
                else -> null
            }

            if (result != null) {
                handleSuccess(result)
            } else {
                // 如果没抓到，尝试模拟滑动唤醒 UI
                if (currentTime % 5000 < 1000) {
                    simulateSwipe()
                }
            }
        } catch (e: Exception) {
            // 忽略异常
        }
    }

    private fun handleAutoClickDialogs(rootNode: AccessibilityNodeInfo) {
        val keywords = listOf("允许", "同意", "打开", "继续", "确认", "我知道了", "以后再说", "不再提示", "关闭", "Open", "Allow", "Accept", "Continue")
        findAndClickNodesByText(rootNode, keywords)
    }

    private fun findAndClickNodesByText(node: AccessibilityNodeInfo, keywords: List<String>) {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        
        for (keyword in keywords) {
            if (text == keyword || desc == keyword) { // 精确匹配优先
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return
                    }
                    parent = parent.parent
                }
            }
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAndClickNodesByText(child, keywords)
        }
    }

    private fun checkIsInvalid(rootNode: AccessibilityNodeInfo): Boolean {
        val invalidKeywords = listOf("链接已失效", "内容已被删除", "页面不存在", "404", "Invalid Link", "Deleted")
        return findTextInNode(rootNode, invalidKeywords)
    }

    private fun findTextInNode(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        for (keyword in keywords) {
            if (text.contains(keyword) || desc.contains(keyword)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findTextInNode(child, keywords)) return true
        }
        return false
    }

    private fun handleInvalid() {
        lastResult = FullResult(isInvalid = true)
        isWaitingForResult = false
        sendResultBroadcast()
    }

    // --- V2.1 强化版抓取逻辑 ---

    private fun parseDouyin(rootNode: AccessibilityNodeInfo): FullResult? {
        // 抖音 ID 经常变，增加多维度匹配
        val likes = findTextById(rootNode, "com.ss.android.ugc.aweme:id/like_count") ?: 
                    findTextByKeywords(rootNode, listOf("赞"), "TextView")
        val comments = findTextById(rootNode, "com.ss.android.ugc.aweme:id/comment_count") ?:
                       findTextByKeywords(rootNode, listOf("评论"), "TextView")
        val shares = findTextById(rootNode, "com.ss.android.ugc.aweme:id/share_count") ?:
                     findTextByKeywords(rootNode, listOf("分享"), "TextView")
        
        if (likes != null) {
            val num = parseLikeNumber(likes)
            return FullResult(likes = likes, likesNumber = num, comments = comments, shares = shares, isAboveThreshold = num >= 50)
        }
        return null
    }

    private fun parseXiaohongshu(rootNode: AccessibilityNodeInfo): FullResult? {
        val likes = findTextById(rootNode, "com.xingin.xhs:id/like_count") ?:
                    findTextByKeywords(rootNode, listOf("赞"), "TextView")
        val comments = findTextById(rootNode, "com.xingin.xhs:id/comment_count")
        
        if (likes != null) {
            val num = parseLikeNumber(likes)
            return FullResult(likes = likes, likesNumber = num, comments = comments, isAboveThreshold = num >= 50)
        }
        return null
    }

    private fun parseToutiao(rootNode: AccessibilityNodeInfo): FullResult? {
        val likes = findTextById(rootNode, "com.ss.android.article.news:id/like_count") ?:
                    findTextByKeywords(rootNode, listOf("赞"), "TextView")
        val fans = findTextByKeywords(rootNode, listOf("粉丝"), "TextView")
        
        if (likes != null) {
            val num = parseLikeNumber(likes)
            return FullResult(fans = fans, likes = likes, likesNumber = num, isAboveThreshold = num >= 50)
        }
        return null
    }

    private fun parseWechat(rootNode: AccessibilityNodeInfo): FullResult? {
        val likes = findTextByKeywords(rootNode, listOf("在看", "点赞"), "TextView")
        if (likes != null) {
            val num = parseLikeNumber(likes)
            return FullResult(likes = likes, likesNumber = num, isAboveThreshold = num >= 50)
        }
        return null
    }

    private fun parseWeibo(rootNode: AccessibilityNodeInfo): FullResult? {
        val likes = findTextById(rootNode, "com.sina.weibo:id/tv_like_count") ?:
                    findTextByKeywords(rootNode, listOf("赞"), "TextView")
        if (likes != null) {
            val num = parseLikeNumber(likes)
            return FullResult(likes = likes, likesNumber = num, isAboveThreshold = num >= 50)
        }
        return null
    }

    private fun parseKuaishou(rootNode: AccessibilityNodeInfo): FullResult? {
        val likes = findTextById(rootNode, "com.smile.gifmaker:id/like_count") ?:
                    findTextByKeywords(rootNode, listOf("赞"), "TextView")
        if (likes != null) {
            val num = parseLikeNumber(likes)
            return FullResult(likes = likes, likesNumber = num, isAboveThreshold = num >= 50)
        }
        return null
    }

    // --- 辅助方法 ---

    private fun findTextById(rootNode: AccessibilityNodeInfo, id: String): String? {
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
        return if (nodes.isNotEmpty()) nodes[0].text?.toString() else null
    }

    private fun findTextByKeywords(node: AccessibilityNodeInfo, keywords: List<String>, className: String? = null): String? {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val nodeClass = node.className?.toString() ?: ""
        
        if (className == null || nodeClass.contains(className)) {
            for (keyword in keywords) {
                if (text.contains(keyword) || desc.contains(keyword)) {
                    // 尝试提取数字部分
                    val num = text.replace(Regex("[^0-9.w万kK]"), "")
                    if (num.isNotEmpty()) return text
                }
            }
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findTextByKeywords(child, keywords, className)
            if (result != null) return result
        }
        return null
    }

    private fun handleSuccess(result: FullResult) {
        lastResult = result
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
        
        // 强化返回逻辑：循环返回 + 显式拉回
        Thread {
            for (i in 1..5) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                Thread.sleep(600)
            }
            // 最终拉回主应用
            val backIntent = Intent(this, MainActivity::class.java)
            backIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(backIntent)
        }.start()
    }

    private fun simulateSwipe() {
        val path = Path()
        path.moveTo(500f, 1500f)
        path.lineTo(500f, 1000f)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
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
