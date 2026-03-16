package com.example.linkchecker.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class LinkItem(
    val url: String,
    val platform: Platform,
    val status: CheckStatus = CheckStatus.PENDING,
    val title: String? = null,
    val author: String? = null,
    val fans: String? = null,
    val likes: String? = null,
    val likesNumber: Int = -1,
    val comments: String? = null,
    val shares: String? = null,
    val isAboveThreshold: Boolean = false,
    val isInvalid: Boolean = false,
    val isChecked: Boolean = false
) : Parcelable

enum class Platform {
    DOUYIN,        // 抖音
    XIAOHONGSHU,   // 小红书
    TOUTIAO,       // 今日头条
    WECHAT,        // 微信文章
    WEIBO,         // 微博
    KUAISHOU,      // 快手
    UNKNOWN        // 未知
}

enum class CheckStatus {
    PENDING,       // 待检测
    CHECKING,      // 检测中
    SUCCESS,       // 检测成功
    FAILED,        // 检测失败
    TIMEOUT,       // 超时
    INVALID        // 链接失效
}
