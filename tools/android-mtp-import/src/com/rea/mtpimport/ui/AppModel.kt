package com.rea.mtpimport.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rea.mtpimport.Catalog
import com.rea.mtpimport.DemoPayload
import com.rea.mtpimport.PhotoWall
import com.rea.mtpimport.Selection
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

    var detailsOpen by mutableStateOf(false)
    var filterOpen by mutableStateOf(false)

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
        state = UiState.State.COLD
    }

    fun showPermission() {
        state = UiState.State.PERMISSION_PENDING
    }

    fun showScanning(done: Int, total: Int) {
        scanDone = done
        scanTotal = total
        state = UiState.State.SCANNING
    }

    fun showEmpty() {
        state = UiState.State.EMPTY_CATALOG
    }

    fun showUnavailable() {
        state = UiState.State.UNAVAILABLE
    }

    fun showError(reason: String) {
        errorReason = reason
        state = UiState.State.ERROR
    }

    fun showCatalog(c: Catalog, s: Selection) {
        catalog = c
        selection = s
        wall = PhotoWall.of(c)
        selectionVersion++
        state = if (wall!!.photoCount() == 0) UiState.State.EMPTY_CATALOG else UiState.State.CATALOG_READY
    }

    fun showImport(doneFiles: Int, totalFiles: Int, doneBytes: Long, totalBytes: Long, bps: Long) {
        updateProgress(doneFiles, totalFiles, doneBytes, totalBytes, bps)
        state = UiState.State.IMPORTING
    }

    /** 进度推进：暂停时保持 `PAUSED`，不把用户按下的暂停顶回 `IMPORTING`。 */
    fun updateProgress(doneFiles: Int, totalFiles: Int, doneBytes: Long, totalBytes: Long, bps: Long) {
        importDoneFiles = doneFiles
        importTotalFiles = totalFiles
        importDoneBytes = doneBytes
        importTotalBytes = totalBytes
        bytesPerSecond = bps
        if (state != UiState.State.PAUSED) state = UiState.State.IMPORTING
    }

    fun showPaused() {
        state = UiState.State.PAUSED
    }

    fun showDone(photos: Int, bytes: Long) {
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
        if (p.catalog != null) {
            // 先装树，再覆盖状态：showCatalog 会把状态改成 CATALOG_READY。
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
