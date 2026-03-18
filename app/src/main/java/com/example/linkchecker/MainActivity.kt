package com.example.linkchecker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.linkchecker.databinding.ActivityMainBinding
import com.example.linkchecker.model.LinkItem
import com.example.linkchecker.model.Platform
import com.example.linkchecker.service.LinkCheckAccessibilityService
import com.example.linkchecker.util.LinkAdapter
import com.example.linkchecker.util.LinkExtractor
import com.example.linkchecker.util.PlatformHandler

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: LinkAdapter
    private val links = mutableListOf<LinkItem>()
    private var currentIndex = -1
    private val handler = Handler(Looper.getMainLooper())
    
    // V2.2 优化：多阶段超时管理
    private val STAGE_TIMEOUT_MS = 8000L   // 每个阶段最多 8 秒
    private val TOTAL_TIMEOUT_MS = 25000L  // 单个链接总超时 25 秒
    private val HEARTBEAT_INTERVAL_MS = 1000L  // 心跳间隔 1 秒
    
    private var stageStartTime = 0L
    private var linkStartTime = 0L
    private val timeoutRunnable = Runnable { handleTimeout() }
    private val heartbeatRunnable = Runnable { checkHeartbeat() }

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.linkchecker.LIKE_RESULT") {
                handler.removeCallbacks(timeoutRunnable)
                handler.removeCallbacks(heartbeatRunnable)
                
                val fans = intent.getStringExtra("fans")
                val likes = intent.getStringExtra("likes")
                val likesNumber = intent.getIntExtra("likesNumber", -1)
                val comments = intent.getStringExtra("comments")
                val shares = intent.getStringExtra("shares")
                val isAboveThreshold = intent.getBooleanExtra("isAboveThreshold", false)
                val isInvalid = intent.getBooleanExtra("isInvalid", false)

                if (currentIndex in links.indices) {
                    val item = links[currentIndex]
                    links[currentIndex] = item.copy(
                        fans = fans ?: item.fans,
                        likes = likes ?: item.likes,
                        likesNumber = if (likesNumber != -1) likesNumber else item.likesNumber,
                        comments = comments ?: item.comments,
                        shares = shares ?: item.shares,
                        isAboveThreshold = isAboveThreshold,
                        isInvalid = isInvalid,
                        isChecked = true
                    )
                    adapter.notifyItemChanged(currentIndex)
                    
                    val elapsedMs = System.currentTimeMillis() - linkStartTime
                    updateProgressUI(item, "完成 (耗时 ${elapsedMs}ms)")
                    
                    // 延迟处理下一个，给系统返回留时间
                    handler.postDelayed({ 
                        forceReturnToLinkChecker()
                        handler.postDelayed({ checkNext() }, 1500)
                    }, 500)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        
        registerReceiver(resultReceiver, IntentFilter("com.example.linkchecker.LIKE_RESULT"), RECEIVER_EXPORTED)
    }

    private fun setupRecyclerView() {
        adapter = LinkAdapter(links)
        binding.recyclerViewLinks.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewLinks.adapter = adapter
    }

    private fun setupListeners() {
        binding.buttonExtract.setOnClickListener {
            val input = binding.editTextInput.text.toString()
            if (input.isBlank()) {
                Toast.makeText(this, "请先输入内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            links.clear()
            links.addAll(LinkExtractor.extract(input))
            adapter.notifyDataSetChanged()
            binding.buttonCheck.isEnabled = links.isNotEmpty()
            binding.textViewStatus.text = "已提取 ${links.size} 条链接"
        }

        binding.buttonCheck.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            
            currentIndex = -1
            binding.layoutProgress.visibility = View.VISIBLE
            binding.progressBar.max = links.size
            binding.textViewProgress.text = "准备开始检测..."
            checkNext()
        }

        binding.buttonAccessibility.setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun checkNext() {
        currentIndex++
        if (currentIndex < links.size) {
            val item = links[currentIndex]
            linkStartTime = System.currentTimeMillis()
            stageStartTime = linkStartTime
            
            updateProgressUI(item, "正在打开应用...")
            
            // 启动无障碍监听状态
            LinkCheckAccessibilityService.startWaiting(item.platform)
            
            // 执行跳转
            PlatformHandler.openLink(this, item)
            
            // 设置多级超时
            handler.postDelayed(timeoutRunnable, TOTAL_TIMEOUT_MS)
            
            // 启动心跳检测
            scheduleHeartbeat()
        } else {
            finishCheck()
        }
    }

    private fun scheduleHeartbeat() {
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
    }

    private fun checkHeartbeat() {
        if (!LinkCheckAccessibilityService.isWaitingForResult) {
            return  // 已完成或已超时
        }

        val currentTime = System.currentTimeMillis()
        val elapsedMs = currentTime - linkStartTime
        
        // 检查总超时
        if (elapsedMs > TOTAL_TIMEOUT_MS) {
            handleTimeout()
            return
        }

        // 检查阶段超时
        val stageElapsedMs = currentTime - stageStartTime
        if (stageElapsedMs > STAGE_TIMEOUT_MS) {
            updateProgressUI(links[currentIndex], "阶段超时，尝试恢复...")
            stageStartTime = currentTime  // 重置阶段计时
        }

        // 继续心跳
        scheduleHeartbeat()
    }

    private fun updateProgressUI(item: LinkItem, status: String = "") {
        binding.progressBar.progress = currentIndex + 1
        val statusText = if (status.isNotEmpty()) " - $status" else ""
        binding.textViewProgress.text = "正在检测 (${currentIndex + 1}/${links.size}): ${item.title ?: item.url}$statusText"
        binding.textViewStatus.text = "平台: ${item.platform.name}"
    }

    private fun handleTimeout() {
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(heartbeatRunnable)
        
        if (currentIndex in links.indices) {
            val item = links[currentIndex]
            links[currentIndex] = item.copy(isChecked = true, isInvalid = false)
            adapter.notifyItemChanged(currentIndex)
            
            val elapsedMs = System.currentTimeMillis() - linkStartTime
            updateProgressUI(item, "超时 (耗时 ${elapsedMs}ms)")
            
            Toast.makeText(this, "链接检测超时，跳过: ${item.platform}", Toast.LENGTH_SHORT).show()
            
            // 强制返回
            forceReturnToLinkChecker()
            
            handler.postDelayed({ checkNext() }, 2000)
        }
    }

    /**
     * 强制返回 LinkChecker 应用
     */
    private fun forceReturnToLinkChecker() {
        try {
            // 方法1：使用全局返回操作
            LinkCheckAccessibilityService.instance?.performGlobalAction(GLOBAL_ACTION_BACK)
            Thread.sleep(300)
            
            // 方法2：再次返回以确保离开目标应用
            LinkCheckAccessibilityService.instance?.performGlobalAction(GLOBAL_ACTION_BACK)
            Thread.sleep(300)
            
            // 方法3：启动 LinkChecker 应用
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        } catch (e: Exception) {
            // 忽略异常
        }
    }

    private fun finishCheck() {
        binding.layoutProgress.visibility = View.GONE
        binding.textViewStatus.text = "检测完成"
        binding.textViewProgress.text = "正在生成报告..."
        
        handler.postDelayed({
            val intent = Intent(this, ResultActivity::class.java)
            intent.putParcelableArrayListExtra("links", ArrayList(links))
            startActivity(intent)
        }, 500)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return LinkCheckAccessibilityService.instance != null
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(resultReceiver)
        handler.removeCallbacksAndMessages(null)
    }
}
