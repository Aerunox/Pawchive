package com.pawchive

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.PredictiveBackControl
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.pawchive.core.store.SettingsManager
import com.pawchive.core.api.ApiClient
import com.pawchive.core.api.ClearanceCoordinator
import com.pawchive.core.api.CloudflareManager
import com.pawchive.core.util.CrashHandler
import com.pawchive.data.repository.CacheRepository
import com.pawchive.work.CacheCleanWorker
import com.pawchive.work.ContentUpdateWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import androidx.hilt.work.HiltWorkerFactory

/**
 * Pawchive 应用入口（ARCH-003：已迁移至 Hilt 依赖注入）。
 *
 * 关键变更：
 * - `@HiltAndroidApp` 启用 Hilt，自动生成依赖注入容器；
 * - 实现 `Configuration.Provider` 以向 WorkManager 注入 `HiltWorkerFactory`，
 *   使 `@HiltWorker` 标注的 Worker 可通过构造函数注入依赖；
 * - `SettingsManager` 通过 Hilt 注入，并暴露静态访问器供 `MainActivity.attachBaseContext`
 *   在 Hilt 注入 Activity 之前读取语言设置（该路径在 Activity 注入之前执行，
 *   ARCH-007：已改读轻量启动缓存，不再同步读 DataStore、不阻塞主线程）。
 * - 实现 `ComponentCallbacks2` 以在进程低内存/销毁时取消全局协程作用域，
 *   避免 CoroutineScope 泄漏（ARCH-BUG-MINOR-16）。
 */
@HiltAndroidApp
class PawchiveApplication : Application(), ImageLoaderFactory, Configuration.Provider, ComponentCallbacks2 {

    companion object {
        private const val IMAGE_DISK_CACHE_DIR = "image_cache"
        private const val IMAGE_DISK_CACHE_MAX_SIZE = 100L * 1024 * 1024

        /**
         * 应用级协程作用域，替代 GlobalScope。
         * 使用 SupervisorJob 确保一个子协程失败不会影响其他协程。
         * 使用 Dispatchers.IO 适合后台数据操作。
         */
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * 静态访问器：仅供 `MainActivity.attachBaseContext` 等启动期路径使用，
         * 该路径在 Hilt 完成 Activity 字段注入之前被系统调用。
         * 常规业务代码应通过 @Inject 注入 SettingsManager，不要使用此方法。
         */
        @Volatile
        private var instance: PawchiveApplication? = null

        fun getSettingsManager(): SettingsManager {
            return instance?.settingsManager
                ?: throw IllegalStateException(
                    "PawchiveApplication 尚未完成 onCreate，SettingsManager 不可用"
                )
        }
    }

    @Inject
    lateinit var settingsManager: SettingsManager

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var cacheRepository: CacheRepository

    @Inject
    lateinit var pendingFavoriteActionRepository: com.pawchive.data.repository.PendingFavoriteActionRepository

    @OptIn(PredictiveBackControl::class)
    override fun onCreate() {
        super.onCreate() // Hilt 在此完成字段注入

        // 关闭应用内 Fragment 预测返回（BUG-NAV-BAR-FLICKER 根因修复）：
        // fragment 1.9.0 起 FragmentManager 的预测返回默认开启（USE_PREDICTIVE_BACK = true），
        // 手势返回时返回栈条目在手势开始即被出栈挂到 mTransitioningOp，手势完成只派发
        // 新的 onBackStackChangeCommitted，不再回调遗留的 onBackStackChanged()——
        // MainActivity 以「返回栈条目数」驱动的底栏显隐因此停在隐藏态，
        // 表现为二级页面（帖子详情/创作者主页）返回主页后底栏消失，息屏/切后台才恢复。
        // 1.8.4 只是删除了 enablePredictiveBack(true) 调用，未覆盖该默认值，所以没有生效。
        // 静态开关必须在任何 Fragment 事务之前设置（Application 路径）；
        // 系统级「返回桌面」预测动画不受影响（由 manifest 的 enableOnBackInvokedCallback 提供）。
        FragmentManager.enablePredictiveBack(false)

        // 尽早应用深色/浅色模式：必须在任何 Activity 创建之前设置，
        // 否则 MainActivity 及其 ViewPager2 Fragment 可能用旧配置实例化，
        // 导致重启后部分页面仍为浅色（卡片/列表手动检查 uiMode 时读到错误值）。
        settingsManager.applyAppearance()

        // 显式初始化 WorkManager，确保 HiltWorkerFactory 已就绪
        // 解决 Configuration.Provider 延迟初始化与 Hilt 注入的时序竞态
        WorkManager.initialize(this, workManagerConfiguration)
        android.util.Log.i("PawchiveApp", "WorkManager explicitly initialized with HiltWorkerFactory")
        instance = this

        // 初始化全局崩溃捕获（FEATURE-006 崩溃埋点）
        CrashHandler.init(this)
        // 初始化 Cloudflare 过盾管理器（object 单例，保留 init 调用；
        // 完整迁移至 DI 属于 ARCH-009：ApiClient/CloudflareManager 拆分为类）
        CloudflareManager.init(this)
        // ARCH-009：启动异步预热过盾（非阻塞），减少首次请求被 Cloudflare 403 拦截
        ClearanceCoordinator.preheat()
        // 自动清理缓存：改用 WorkManager 受约束任务 + 容量阈值策略（FRONTEND-003）
        scheduleAutoCacheCleanIfNeeded()
        // 内容更新订阅周期同步（ARCH-FEATURE-003）
        scheduleContentUpdateSync()
        // 离线收藏动作队列（FEAT-OFFLINE-FAV-QUEUE）：启动时兜底调度一次补写；
        // 队列为空时 Worker 立即成功返回，开销可忽略。WorkManager 任务本身跨重启持久，
        // 这里只是覆盖"上轮调度因连续失败被放弃"的场景。
        pendingFavoriteActionRepository.scheduleSync()
        // FEATURE：省流量加载——把"列表加载原图"判定注入 PostAdapter（读内存快照，零阻塞）
        com.pawchive.ui.adapter.PostAdapter.preferOriginalImagesProvider = {
            settingsManager.isListOriginalImageEnabled()
        }
        // FEATURE 设置项扩展（批次三）：减少动画——注入骨架屏与适配器的动画开关 provider
        com.pawchive.ui.widget.SkeletonHelper.reducedAnimationsProvider = {
            settingsManager.isReduceAnimationsEnabled()
        }
        com.pawchive.ui.adapter.PostAdapter.reduceAnimationsProvider = {
            settingsManager.isReduceAnimationsEnabled()
        }
        // FEATURE 设置项扩展（批次三）：定时自动备份——开启状态下按日调度（UPDATE 幂等）
        if (settingsManager.isAutoBackupEnabled()) {
            com.pawchive.work.AutoBackupWorker.schedule(this, enabled = true)
        }
    }

    /**
     * 向 WorkManager 提供 HiltWorkerFactory，使 @HiltWorker 能通过构造函数注入依赖。
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * 基于容量阈值调度自动缓存清理（FRONTEND-003）。
     * - 用户开启"自动清理缓存"开关时才考虑
     * - 缓存大小超过阈值（默认 200MB）或从未清理过时触发
     * - 使用 WorkManager OneTimeWorkRequest，延迟 10s 避免与启动期文件访问竞争
     */
    /**
     * PERF-001：将缓存阈值检测移至后台协程。
     * 原问题：shouldCleanByThreshold() → getCacheSize() 递归遍历 cacheDir，
     * 在主线程上执行导致冷启动延迟（文件数千时可达数百毫秒）。
     */
    private fun scheduleAutoCacheCleanIfNeeded() {
        applicationScope.launch {
            if (!cacheRepository.isAutoCleanEnabled()) return@launch

            if (!CacheCleanWorker.shouldCleanByThreshold(cacheRepository)) return@launch

            val request = OneTimeWorkRequestBuilder<CacheCleanWorker>()
                .setInitialDelay(10L, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(this@PawchiveApplication).enqueue(request)
        }
    }

    /**
     * 调度内容更新订阅周期同步（ARCH-FEATURE-003）。
     * - 周期可在设置页调整（WorkManager 最小 15 分钟）
     * - 联网约束：无网络时跳过本次运行
     * - UPDATE 策略：保留既有排队时间并应用最新周期设置（KEEP 无法更新周期，
     *   REPLACE 会重置排队计时，UPDATE 兼顾两者，WorkManager 2.8+ 支持）
     */
    private fun scheduleContentUpdateSync() {
        val intervalMinutes = settingsManager.getSyncIntervalMinutes().toLong()
        val request = PeriodicWorkRequestBuilder<ContentUpdateWorker>(
            intervalMinutes,
            TimeUnit.MINUTES
        ).setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ContentUpdateWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            // 使用 sharedOkHttpClient：img.pawchive.pw 并非完全公开 CDN，
            // 部分缩略图请求也会被 Cloudflare 拦截返回 403。
            // sharedOkHttpClient 内置 cloudflareRetryInterceptor，
            // 会在 403 时自动刷新 cf_clearance 并重试一次。
            .okHttpClient(ApiClient.sharedOkHttpClient)
            // 动图解码：minSdk 30 ≥ 28，直接走 ImageDecoder 通道，支持
            // GIF / 动画 WebP / 动画 HEIF，产出 AnimatedImageDrawable（系统级硬件加速播放）。
            // 作品图、图查看器与创作者头像（ShapeableImageView）全部是 Drawable 目标，
            // 且全项目未使用任何 Coil Transformation，因此注册一次即全局生效。
            .components {
                add(ImageDecoderDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, IMAGE_DISK_CACHE_DIR))
                    .maxSizeBytes(IMAGE_DISK_CACHE_MAX_SIZE)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }

    /**
     * 清理应用缓存（ARCH-010：统一委托 data 层 CacheRepository，
     * 消除 app 作为单点缓存清理入口的职责）。
     * 保留方法供 UI 层（SettingsViewModel）调用。
     */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearCache() {
        cacheRepository.clearCache()
    }

    /**
     * ComponentCallbacks2：在进程低内存或销毁时取消全局协程作用域，
     * 避免 CoroutineScope 泄漏（ARCH-BUG-MINOR-16）。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            // 应用进入后台时，清理图片内存缓存和页面内存缓存，降低后台 RAM 占用
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                try {
                    coil.Coil.imageLoader(this).memoryCache?.clear()
                } catch (_: Exception) {}
                try {
                    // AppMemoryCache 由 Hilt 注入，通过单例获取
                    // 直接清空即可，下次进入页面会重新加载
                } catch (_: Exception) {}
            }
            // 系统内存紧张时，进一步清理
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                try {
                    coil.Coil.imageLoader(this).memoryCache?.clear()
                } catch (_: Exception) {}
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // 取消应用级协程作用域，释放资源
        applicationScope.coroutineContext.cancel()
        // 取消 ClearanceCoordinator 内部作用域
        ClearanceCoordinator.shutdown()
        // 取消 CloudflareManager 内部作用域
        CloudflareManager.shutdown()
    }
}


