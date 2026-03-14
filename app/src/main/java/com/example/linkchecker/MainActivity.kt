package com.example.linkchecker

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.linkchecker.databinding.ActivityMainBinding
import com.example.linkchecker.model.CheckStatus
import com.example.linkchecker.model.LinkItem
import com.example.linkchecker.service.LinkCheckAccessibilityService
import com.example.linkchecker.util.LinkAdapter
import com.example.linkchecker.util.LinkExtractor
import com.example.linkchecker.util.PlatformHandler

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var linkAdapter: LinkAdapter
    private var linkList = mutableListOf<LinkItem>()
    private var currentCheckIndex = 0
    private var isChecking = false
    private val handler = Handler(Looper.getMainLooper())
    
    private val checkTimeout = 15000L // 15秒超时
    private var checkRunnable: Runnable? = null

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.linkchecker.LIKE_RESULT") {
                val found = intent.getBooleanExtra("found", false)
                val likes = intent.getStringExtra("likes") ?: ""
                val likesNumber = intent.getIntExtra("likesNumber", -1)
                val isAboveThreshold = intent.getBooleanExtra("isAboveThreshold", false)

                if (found && currentCheckIndex < linkList.size) {
                    updateCurrentLinkResult(likes, likesNumber, isAboveThreshold)
                }

                // 延迟后检查下一个
                handler.postDelayed({ checkNextLink() }, 2000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupButtons()
        registerReceiver()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(resultReceiver)
        handler.removeCallbacksAndMessages(null)
    }

    private fun setupRecyclerView() {
        linkAdapter = LinkAdapter(linkList)
        binding.recyclerViewLinks.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = linkAdapter
        }
    }

    private fun setupButtons() {
        binding.buttonExtract.setOnClickListener {
            extractLinks()
        }

        binding.buttonCheck.setOnClickListener {
            if (isAccessibilityServiceEnabled()) {
                startChecking()
            } else {
                showAccessibilityDialog()
            }
        }

        binding.buttonAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter("com.example.linkchecker.LIKE_RESULT")
        registerReceiver(resultReceiver, filter)
    }

    private fun extractLinks() {
        val text = binding.editTextInput.text.toString()
        if (text.isEmpty()) {
            Toast.makeText(this, "请先粘贴聊天记录", Toast.LENGTH_SHORT).show()
            return
        }

        linkList.clear()
        linkList.addAll(LinkExtractor.extractLinks(text))
        currentCheckIndex = 0

        if (linkList.isEmpty()) {
            binding.textViewStatus.text = getString(R.string.no_links_found)
            binding.buttonCheck.isEnabled = false
            Toast.makeText(this, "未找到链接", Toast.LENGTH_SHORT).show()
        } else {
            binding.textViewStatus.text = getString(R.string.found_links, linkList.size)
            binding.buttonCheck.isEnabled = true
            Toast.makeText(this, "找到 ${linkList.size} 个链接", Toast.LENGTH_SHORT).show()
        }

        linkAdapter.notifyDataSetChanged()
    }

    private fun startChecking() {
        if (linkList.isEmpty()) {
            Toast.makeText(this, "没有链接可检测", Toast.LENGTH_SHORT).show()
            return
        }

        if (isChecking) {
            Toast.makeText(this, "检测进行中...", Toast.LENGTH_SHORT).show()
            return
        }

        isChecking = true
        currentCheckIndex = 0
        
        // 重置所有状态
        linkList = linkList.map { it.copy(status = CheckStatus.PENDING) }.toMutableList()
        linkAdapter.notifyDataSetChanged()

        checkNextLink()
    }

    private fun checkNextLink() {
        // 取消之前的超时检测
        checkRunnable?.let { handler.removeCallbacks(it) }

        if (currentCheckIndex >= linkList.size) {
            finishChecking()
            return
        }

        val linkItem = linkList[currentCheckIndex]
        
        // 更新为检测中状态
        linkList[currentCheckIndex] = linkItem.copy(status = CheckStatus.CHECKING)
        linkAdapter.notifyItemChanged(currentCheckIndex)
        binding.textViewStatus.text = getString(R.string.checking, 
            "${LinkExtractor.getPlatformName(linkItem.platform)} ${currentCheckIndex + 1}/${linkList.size}")

        // 通知 AccessibilityService 开始等待
        LinkCheckAccessibilityService.reset()
        LinkCheckAccessibilityService.instance?.startWaiting(linkItem.platform)

        // 打开链接
        val success = PlatformHandler.openLink(this, linkItem)
        if (!success) {
            updateCurrentLinkFailed()
            handler.postDelayed({ checkNextLink() }, 1000)
            return
        }

        // 设置超时
        checkRunnable = Runnable {
            if (currentCheckIndex < linkList.size && linkList[currentCheckIndex].status == CheckStatus.CHECKING) {
                // 超时，标记为失败
                updateCurrentLinkTimeout()
                checkNextLink()
            }
        }
        handler.postDelayed(checkRunnable!!, checkTimeout)
    }

    private fun updateCurrentLinkResult(likes: String, likesNumber: Int, isAboveThreshold: Boolean) {
        if (currentCheckIndex >= linkList.size) return
        
        val linkItem = linkList[currentCheckIndex]
        linkList[currentCheckIndex] = linkItem.copy(
            status = CheckStatus.SUCCESS,
            likes = likes,
            likesNumber = likesNumber,
            isAboveThreshold = isAboveThreshold
        )
        linkAdapter.notifyItemChanged(currentCheckIndex)
        currentCheckIndex++
    }

    private fun updateCurrentLinkFailed() {
        if (currentCheckIndex >= linkList.size) return
        
        val linkItem = linkList[currentCheckIndex]
        linkList[currentCheckIndex] = linkItem.copy(status = CheckStatus.FAILED)
        linkAdapter.notifyItemChanged(currentCheckIndex)
        currentCheckIndex++
    }

    private fun updateCurrentLinkTimeout() {
        if (currentCheckIndex >= linkList.size) return
        
        val linkItem = linkList[currentCheckIndex]
        linkList[currentCheckIndex] = linkItem.copy(status = CheckStatus.TIMEOUT)
        linkAdapter.notifyItemChanged(currentCheckIndex)
        currentCheckIndex++
    }

    private fun finishChecking() {
        isChecking = false
        binding.textViewStatus.text = getString(R.string.check_complete)
        
        // 显示结果
        val aboveThresholdCount = linkList.count { it.isAboveThreshold }
        Toast.makeText(this, 
            "检测完成! 共有 ${aboveThresholdCount} 个链接点赞≥50", 
            Toast.LENGTH_LONG).show()

        // 打开结果页面
        val intent = Intent(this, ResultActivity::class.java).apply {
            putParcelableArrayListExtra("links", ArrayList(linkList))
        }
        startActivity(intent)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        
        for (service in enabledServices) {
            if (service.resolveInfo.serviceInfo.packageName == packageName) {
                return true
            }
        }
        return false
    }

    private fun updateAccessibilityButton() {
        if (isAccessibilityServiceEnabled()) {
            binding.buttonAccessibility.text = "无障碍服务已开启"
            binding.buttonAccessibility.isEnabled = false
        } else {
            binding.buttonAccessibility.text = "开启无障碍服务"
            binding.buttonAccessibility.isEnabled = true
        }
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要无障碍权限")
            .setMessage("本应用需要无障碍服务权限才能自动读取点赞数。请在设置中找到\"链接点赞检测服务\"并开启。")
            .setPositiveButton("去开启") { _, _ ->
                openAccessibilitySettings()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "请找到\"链接点赞检测服务\"并开启", Toast.LENGTH_LONG).show()
    }
}
