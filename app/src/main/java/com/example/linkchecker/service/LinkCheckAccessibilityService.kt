package com.example.linkchecker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return
        
        // 1. 自动点击“同意/打开”弹窗 (深度扫描)
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
        // 常见的跳转确认按钮文本
        val keywords = listOf("允许", "同意", "打开", "继续", "确认", "我知道了", "Open", "Allow", "Accept", "Continue")
        
        // 递归查找并点击
        findAndClickNodesByText(rootNode, keywords)
    }

    private fun findAndClickNodesByText(node: AccessibilityNodeInfo, keywords: List<String>) {
        for (keyword in keywords) {
            if (node.text?.toString()?.contains(keyword) == true || node.contentDescription?.toString()?.contains(keyword) == true) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
                // 如果父节点可点击，点击父节点
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

    // --- 平台抓取逻辑 (增强 ID 识别) ---

    private fun parseDouyin(rootNode: AccessibilityNodeInfo) {
        val likes = findTextById(rootNode, "com.ss.android.ugc.aweme:id/like_count")
        val comments = findTextById(rootNode, "com.ss.android.ugc.aweme:id/comment_count")
        val shares = findTextById(rootNode, "com.ss.android.ugc.aweme:id/share_count")
        
        if (likes != null) {
            val num = parseLikeNumber(likes)
            handleSuccess(null, likes, num, comments, shares)
        }
    }

    private fun parseXiaohongshu(rootNode: AccessibilityNodeInfo) {
        val likes = findTextById(rootNode, "com.xingin.xhs:id/like_count")
        val comments = findTextById(rootNode, "com.xingin.xhs:id/comment_count")
        
        if (likes != null) {
            val num = parseLikeNumber(likes)
            handleSuccess(null, likes, num, comments, null)
        }
    }

    private fun parseToutiao(rootNode: AccessibilityNodeInfo) {
        // 今日头条粉丝量通常在详情页底部或点击头像后
        val likes = findTextById(rootNode, "com.ss.android.article.news:id/like_count")
        val fans = findTextById(rootNode, "com.ss.android.article.news:id/fans_count") ?: 
                   findTextByKeywords(rootNode, listOf("粉丝"))
        
        if (likes != null) {
            val num = parseLikeNumber(likes)
            handleSuccess(fans, likes, num, null, null)
        }
    }

    private fun parseWechat(rootNode: AccessibilityNodeInfo) {
        val likes = findTextByKeywords(rootNode, listOf("在看", "点赞"))
        if (likes != null) {
            val num = parseLikeNumber(likes)
            handleSuccess(null, likes, num, null, null)
        }
    }

    private fun parseWeibo(rootNode: AccessibilityNodeInfo) {
        val likes = findTextById(rootNode, "com.sina.weibo:id/tv_like_count")
        if (likes != null) {
            val num = parseLikeNumber(likes)
            handleSuccess(null, likes, num, null, null)
        }
    }

    private fun parseKuaishou(rootNode: AccessibilityNodeInfo) {
        val likes = findTextById(rootNode, "com.smile.gifmaker:id/like_count")
        if (likes != null) {
            val num = parseLikeNumber(likes)
            handleSuccess(null, likes, num, null, null)
        }
    }

    // --- 辅助方法 ---

    private fun findTextById(rootNode: AccessibilityNodeInfo, id: String): String? {
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
        return if (nodes.isNotEmpty()) nodes[0].text?.toString() else null
    }

    private fun findTextByKeywords(node: AccessibilityNodeInfo, keywords: List<String>): String? {
        val text = node.text?.toString() ?: ""
        for (keyword in keywords) {
            if (text.contains(keyword)) return text
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findTextByKeywords(child, keywords)
            if (result != null) return result
        }
        return null
    }

    private fun handleSuccess(fans: String?, likes: String?, likesNumber: Int, comments: String?, shares: String?) {
        lastResult = FullResult(
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
        
        // 强化返回逻辑：尝试多次返回，并最终拉回主应用
        Thread {
            for (i in 1..3) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                Thread.sleep(800)
            }
        }.start()
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
