package com.rea.mtpimport.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rea.mtpimport.Catalog
import com.rea.mtpimport.DemoPayload
import com.rea.mtpimport.PhotoPreview
import com.rea.mtpimport.PhotoWall
import com.rea.mtpimport.Selection
import com.rea.mtpimport.ThumbnailStore
import com.rea.mtpimport.UiState

/** 界面 → 业务层的动作出口；由 Main 实现（Java 侧实现 Kotlin 接口最省事）。 */
interface AppCallbacks {
    fun onConnectCamera()
    fun onAllow()
    fun onCancel()
    fun onPauseResume()
    fun onImport()
    fun onOpen()
    fun onBack()
    fun onCloseTransfer()
    fun onPauseExit()
    fun onResumeExit()
    fun onDemoState(name: String)
}

/** 过滤抽屉的三种口径（U-52 要求 `All` / `RAW` / 日期三项都在）。 */
enum class FilterKind { ALL, RAW, DATE }

/**
 * Compose 侧的状态持有者。只做「状态映射」：所有 USB / 扫描 / 传输逻辑仍在 Main 里，
 * 这里接收它推过来的结果，转成 10 个界面态需要的字段。
 *
 * Java 侧通过 `setXxx()` 方法写入，避免暴露 Compose 的 MutableState 给 Java。
 */
class AppModel {

    var state by mutableStateOf(UiState.State.COLD)
        private set

    var catalog by mutableStateOf<Catalog?>(null)
        private set
    var wall by mutableStateOf<PhotoWall?>(null)
        private set
    var selection by mutableStateOf<Selection?>(null)
        private set

    /** Selection 不是 observable；每次勾选/清空后自增，Compose 靠它重绘。 */
    var selectionVersion by mutableIntStateOf(0)
        private set

    var scanDone by mutableIntStateOf(0)
    var scanTotal by mutableIntStateOf(0)

    /**
     * 渐进态（issue #60）：照片墙已发布、扫描线程仍在后台补齐。
     * 不是新状态——界面仍是 `CATALOG_READY`，这一个标志只控制
     * 顶栏进度细线 / 临时 `Cancel` 与主 CTA 的 `enabled`（UI-SPEC §1.2 渐进态）。
     */
    var backgroundScanning by mutableStateOf(false)
        private set

    var importDoneFiles by mutableIntStateOf(0)
    var importTotalFiles by mutableIntStateOf(0)
    var importDoneBytes by mutableLongStateOf(0L)
    var importTotalBytes by mutableLongStateOf(0L)
    var bytesPerSecond by mutableLongStateOf(0L)

    var donePhotos by mutableIntStateOf(0)
    var doneBytes by mutableLongStateOf(0L)

    var errorReason by mutableStateOf("")
        private set

    var deviceName by mutableStateOf("a7C")
        private set

    /** 只在 `Location details` 里出现的保存位置（§0.10 的口径）。 */
    var locationPath by mutableStateOf("")
        private set

    var demoActive by mutableStateOf(false)
        private set

    var openedPhoto by mutableStateOf<Catalog.Entry?>(null)
        private set

    /**
     * 照片详情页的预览来源（issue #46）。真实 MTP 路径没有实现（相机里的 ARW 未取回），
     * 恒为 null，页面按 U-51 只显示胶囊；debug 的 demo 模式注入 `DemoImages`。
     */
    var previewSource: PhotoPreview? = null

    /** 照片墙缩略图（issue #59）：由 Main 注入；demo 与真实 MTP 走同一个 store。 */
    var thumbnails by mutableStateOf<ThumbnailStore?>(null)
        private set

    var detailsOpen by mutableStateOf(false)
    var filterOpen by mutableStateOf(false)

    /**
     * `IMPORTING` / `PAUSED` 下按返回键弹出的确认框（`Pause & exit` / `Resume`，§1.3）。
     * 只有系统返回键会打开它；顶部返回只关视图，不弹框。
     */
    var exitConfirmOpen by mutableStateOf(false)
        private set

    /**
     * 顶部返回只关闭传输视图（§1.3 第 2 条）：任务继续跑，所以进度回调**不得**把界面
     * 拽回传输屏；下一次 `showImport` 或完成时复位。
     */
    var transferViewClosed by mutableStateOf(false)
        private set

    var filterKind by mutableStateOf(FilterKind.ALL)
        private set
    var filterDate: String? = null
        private set

    /** 折叠的日期组 key（整行进 L-08 的可点范围）。 */
    val collapsed = mutableStateListOf<String>()

    var logLines by mutableStateOf<List<String>>(emptyList())
        private set

    var callbacks: AppCallbacks? = null

    val photoCount: Int get() = wall?.photoCount() ?: 0
    val selectedCount: Int get() = selection?.fileHandles()?.size ?: 0
    val allGroups: List<PhotoWall.Group> get() = wall?.groups() ?: emptyList()

    val visibleGroups: List<PhotoWall.Group>
        get() {
            val groups = allGroups
            return when (filterKind) {
                FilterKind.ALL, FilterKind.RAW -> groups
                FilterKind.DATE -> groups.filter { it.key == filterDate }
            }
        }

    // 名字不能叫 `setDeviceName` / `setLocationPath`：这两个 property 的 setter 已经占用
    // `setDeviceName(Ljava/lang/String;)V` 这个 JVM 签名，再写同名方法会平台声明冲突。
    fun applyDeviceName(name: String) {
        deviceName = com.rea.mtpimport.UiText.deviceName(name)
    }

    fun applyLocationPath(path: String) {
        locationPath = path
    }

    fun showCold() {
        backgroundScanning = false
        state = UiState.State.COLD
    }

    fun showPermission() {
        backgroundScanning = false
        state = UiState.State.PERMISSION_PENDING
    }

    /**
     * 扫描进度回调。渐进发布之后（[backgroundScanning] 为 true）只更新细线分母，
     * **不得**把界面从 `CATALOG_READY` 拽回 `SCANNING`——照片墙已经能用了。
     */
    fun showScanning(done: Int, total: Int) {
        scanDone = done
        scanTotal = total
        if (backgroundScanning) return
        state = UiState.State.SCANNING
    }

    /**
     * 渐进发布（issue #60）：把已发现的照片先送上照片墙，扫描继续在后台补齐。
     * 只在已发现 ARW 时调用；首次调用会从 `SCANNING` 切到 `CATALOG_READY`。
     */
    fun showPartialCatalog(c: Catalog, s: Selection, done: Int, total: Int) {
        catalog = c
        selection = s
        wall = PhotoWall.of(c)
        scanDone = done
        scanTotal = total
        selectionVersion++
        if ((wall?.photoCount() ?: 0) == 0) {
            state = UiState.State.SCANNING
            return
        }
        backgroundScanning = true
        state = UiState.State.CATALOG_READY
    }

    /** 取消扫描：已渐进发布的部分结果整批撤掉（issue #60 的取消语义）。 */
    fun discardPartialCatalog() {
        if (!backgroundScanning) return
        catalog = null
        wall = null
        selection = null
        selectionVersion++
        backgroundScanning = false
    }

    fun showEmpty() {
        backgroundScanning = false
        state = UiState.State.EMPTY_CATALOG
    }

    fun showUnavailable() {
        backgroundScanning = false
        state = UiState.State.UNAVAILABLE
    }

    fun showError(reason: String) {
        errorReason = reason
        backgroundScanning = false
        state = UiState.State.ERROR
    }

    fun showCatalog(c: Catalog, s: Selection) {
        catalog = c
        selection = s
        wall = PhotoWall.of(c)
        selectionVersion++
        backgroundScanning = false
        state = if (wall!!.photoCount() == 0) UiState.State.EMPTY_CATALOG else UiState.State.CATALOG_READY
    }

    fun showImport(doneFiles: Int, totalFiles: Int, doneBytes: Long, totalBytes: Long, bps: Long) {
        transferViewClosed = false
        exitConfirmOpen = false
        updateProgress(doneFiles, totalFiles, doneBytes, totalBytes, bps)
        state = UiState.State.IMPORTING
    }

    /** 进度推进：暂停时保持 `PAUSED`；传输视图已关闭时保持当前视图，不抢回传输屏。 */
    fun updateProgress(doneFiles: Int, totalFiles: Int, doneBytes: Long, totalBytes: Long, bps: Long) {
        importDoneFiles = doneFiles
        importTotalFiles = totalFiles
        importDoneBytes = doneBytes
        importTotalBytes = totalBytes
        bytesPerSecond = bps
        if (!transferViewClosed && state != UiState.State.PAUSED) state = UiState.State.IMPORTING
    }

    /**
     * 离开传输视图：`Pause & exit` / `Cancel` 与顶部返回共用。
     *
     * 先置 `transferViewClosed` 再回照片墙（issue #48 遗留竞态）：worker 可能正把当前文件传完，
     * 它迟到的 `onProgress` 会走 [updateProgress]；只有视图标记为「已关闭」时，
     * 进度才允许更新数值、而不是把界面切回 `IMPORTING`。
     */
    fun leaveTransferView() {
        transferViewClosed = true
        exitConfirmOpen = false
        backToCatalog()
    }

    /** transfer 顶部返回：只关视图（回 `CATALOG_READY`），不取消任务（§1.3）。 */
    fun closeTransferView() {
        leaveTransferView()
    }

    // 名字不能叫 `setExitConfirmOpen`：`exitConfirmOpen` 这个 property 的 setter 已经占用
    // `setExitConfirmOpen(Z)V`，再写同名方法会平台声明冲突。
    fun setExitConfirm(open: Boolean) {
        exitConfirmOpen = open
    }

    fun showPaused() {
        state = UiState.State.PAUSED
    }

    fun showDone(photos: Int, bytes: Long) {
        transferViewClosed = false
        exitConfirmOpen = false
        donePhotos = photos
        doneBytes = bytes
        state = UiState.State.DONE
    }

    /** 取消传输/关闭传输视图：有照片墙就回到 `CATALOG_READY`，否则回 `COLD`。 */
    fun backToCatalog() {
        state = if ((wall?.photoCount() ?: 0) > 0) UiState.State.CATALOG_READY else UiState.State.COLD
    }

    fun toggleSelection(entry: Catalog.Entry) {
        selection?.toggle(entry.handle)
        selectionVersion++
    }

    fun openPhoto(entry: Catalog.Entry) {
        openedPhoto = entry
    }

    /** 该照片的预览图；没有来源或没有素材时返回 null。 */
    fun previewFor(entry: Catalog.Entry): Bitmap? = previewSource?.previewFor(entry)

    fun bindThumbnails(store: ThumbnailStore) {
        thumbnails = store
    }

    /** 读网格缩略图（**必须**在 IO 线程调用）；null 表示该条目没有可用预览。 */
    fun loadThumbnail(entry: Catalog.Entry): Bitmap? = thumbnails?.load(entry)

    fun closePhoto() {
        openedPhoto = null
    }

    fun toggleCollapsed(key: String) {
        if (!collapsed.remove(key)) collapsed.add(key)
    }

    fun isCollapsed(key: String): Boolean = collapsed.contains(key)

    fun setFilter(kind: FilterKind, date: String? = null) {
        filterKind = kind
        filterDate = date
    }

    fun addLog(line: String) {
        val next = (logLines + line).takeLast(200)
        logLines = next
    }

    /**
     * demo 载荷 → 界面状态。目录态会同时把假树装进来，这样从 `IMPORTING` 取消后
     * 还能回到照片墙继续点选（T-03）。
     */
    fun applyDemo(p: DemoPayload?) {
        if (p == null) return
        demoActive = true
        transferViewClosed = false
        exitConfirmOpen = false
        if (p.catalog != null && catalog == null) {
            // 只在还没有照片墙时才建树：从 READY 切 ERROR 等态必须保留已有 wall / selection，
            // 否则「ERROR 返回照片墙、选择还在」（§1.3）演不出来。
            // `Selection(p.catalog)` 初始是**空集**（Selection.java:20-22），不是全选；
            // `showCatalog` 会把状态改成 CATALOG_READY，先记住原状态，装完树再覆盖回来。
            val prev = state
            showCatalog(p.catalog, Selection(p.catalog))
            if (prev != UiState.State.CATALOG_READY) state = prev
        }
        when (p.state) {
            UiState.State.CATALOG_READY -> {
                state = UiState.State.CATALOG_READY
                importDoneFiles = p.doneFiles
                importTotalFiles = p.totalFiles
            }
            UiState.State.SCANNING -> showScanning(p.scanned, p.scanTotal)
            UiState.State.IMPORTING, UiState.State.PAUSED -> {
                importDoneFiles = p.doneFiles
                importTotalFiles = p.totalFiles
                importDoneBytes = p.doneBytes
                importTotalBytes = p.totalBytes
                bytesPerSecond = p.bytesPerSecond
                state = p.state
            }
            UiState.State.DONE -> showDone(p.donePhotos, p.resultBytes)
            UiState.State.ERROR -> showError(p.errorReason)
            UiState.State.EMPTY_CATALOG -> {
                catalog = null
                wall = null
                selection = null
                state = UiState.State.EMPTY_CATALOG
            }
            else -> state = p.state
        }
        addLog("[demo] 切到 " + p.state.name)
    }
}
