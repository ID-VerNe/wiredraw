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

    /**
     * 计划变更（PD / P-09）：勾选或 filter 变化都会改变可见集合与载荷大小，
     * Main 用它重算空间预检，决定主 CTA 是否禁用。
     */
    fun onSelectionChanged()

    /** 回到照片墙（PD / P-09）：DONE / ERROR / 取消返回时同样要重算空间预检。 */
    fun onBackToCatalog()
}

/**
 * 过滤抽屉的四类口径（U-52：`All` / `RAW` / `JPG` 各一项 + 日期逐行）。
 *
 * filter 只决定「显示哪些照片单元」，不改变像素来源；载荷格式只作用在导出侧（`V-16` / `V-32`）。
 */
enum class FilterKind { ALL, RAW, JPG, DATE }

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

    // 传输进度只按文件计数：环内百分比与环下 `a/b` 同源（UI-SPEC `U-45` / `P-03`）。
    // 字节数与速率不建界面状态、不上屏——#177 已证 `getPartialObject` 的 offset 真机不生效，
    // 「已落盘字节」不可信；`DONE` 的容量统计走 `showDone`，与本状态无关。
    var importDoneFiles by mutableIntStateOf(0)
    var importTotalFiles by mutableIntStateOf(0)

    var donePhotos by mutableIntStateOf(0)
    var doneBytes by mutableLongStateOf(0L)

    var errorReason by mutableStateOf("")
        private set

    var deviceName by mutableStateOf("a7C")
        private set

    /** 只在 `Location details` 里出现的保存位置（§0.10 的口径）。 */
    var locationPath by mutableStateOf("")
        private set

    /**
     * 空间预检结果（PD / P-09）。`lowSpace` 为真时照片墙主 CTA 禁用并显示一行 `Low space`，
     * 差额只进 Details（`lowSpaceDetail`）；主视觉不出现容量数字。
     */
    var lowSpace by mutableStateOf(false)
        private set
    var lowSpaceDetail by mutableStateOf("")
        private set

    /**
     * 逐文件失败明细（PD / issue #50）。DONE 首屏受 U-47 约束不能出现 `Failed N`，
     * 所以固定在 Details 抽屉里展示；最多保留 20 条，不会被日志的 takeLast(8) 挤掉。
     */
    var failedLines by mutableStateOf<List<String>>(emptyList())
        private set

    /**
     * 本轮续传跳过数（issue #53 / `P-07` / `U-21`）：只进 `Details` 抽屉，主视觉不出现
     * （`U-47` 同样禁止 `DONE` 首屏出现 `Skipped N existing`）。由 `Main.onFinished` 按本轮
     * `skipped` 写入；没有跳过时由 `UiText.skippedLine` 折成空串，不显示「跳过 0」。
     */
    var skippedLine by mutableStateOf("")
        private set

    var demoActive by mutableStateOf(false)
        private set

    var openedPhoto by mutableStateOf<PhotoWall.Unit?>(null)
        private set

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

    /** 全部照片单元数（只用于空态判定；CTA / 胶囊的分母见 [visibleExportCount]）。 */
    val photoCount: Int get() = wall?.photoCount() ?: 0

    /** 当前档位对应的导出载荷（`V-32`）；`All` / 日期档 JPG 优先、`RAW` 只要 ARW、`JPG` 只要 JPG。 */
    val exportMode: PhotoWall.Export
        get() = when (filterKind) {
            FilterKind.RAW -> PhotoWall.Export.RAW
            FilterKind.JPG -> PhotoWall.Export.JPG
            else -> PhotoWall.Export.JPG_FIRST
        }

    val allUnits: List<PhotoWall.Unit> get() = wall?.units() ?: emptyList()

    /**
     * 当前 filter 可见的照片单元集合。
     *
     * 这是「所见即所得」的唯一分母来源（issue #83）：导出计划、CTA 文案、顶栏胶囊都取它；
     * 已勾选但被 filter 隐藏的项保留在 [selection] 里，只是不计入本轮可见集合。
     */
    val visibleUnits: List<PhotoWall.Unit>
        get() = when (filterKind) {
            FilterKind.ALL -> allUnits
            FilterKind.RAW -> allUnits.filter { it.hasArw() }
            FilterKind.JPG -> allUnits.filter { it.hasJpg() }
            FilterKind.DATE -> visibleGroups.flatMap { it.photos }
        }

    /** 可见集合的导出文件数（每单元恒 1 个文件）；顶栏胶囊与主 CTA 同分母（`C-08`）。 */
    val visibleExportCount: Int get() = visibleUnits.size

    /** 可见已选数（CTA 的 `N`，`C-10`）；隐藏的已选项不计。 */
    val visibleSelectedCount: Int get() = visibleUnits.count { it.isChecked(selection) }

    val allGroups: List<PhotoWall.Group> get() = wall?.groups() ?: emptyList()

    val visibleGroups: List<PhotoWall.Group>
        get() = when (filterKind) {
            FilterKind.ALL -> allGroups
            FilterKind.DATE -> allGroups.filter { it.key == filterDate }
            FilterKind.RAW -> allGroups.mapNotNull { g ->
                g.select(PhotoWall.UnitFilter { u -> u.hasArw() })
            }
            FilterKind.JPG -> allGroups.mapNotNull { g ->
                g.select(PhotoWall.UnitFilter { u -> u.hasJpg() })
            }
        }

    /** 顶栏 filter 芯片（`V-31`）：`All` 档不显示；日期档取组标签（如 `Jun 7`）。 */
    val filterChip: String?
        get() = when (filterKind) {
            FilterKind.ALL -> null
            FilterKind.RAW -> "RAW"
            FilterKind.JPG -> "JPG"
            FilterKind.DATE -> allGroups.firstOrNull { it.key == filterDate }?.label
        }

    fun isUnitChecked(unit: PhotoWall.Unit): Boolean = unit.isChecked(selection)

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
     * 已发现第一批照片单元（ARW 或 JPG，issue #96）时调用；首次调用会从 `SCANNING` 切到 `CATALOG_READY`。
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

    fun showImport(doneFiles: Int, totalFiles: Int) {
        transferViewClosed = false
        exitConfirmOpen = false
        updateProgress(doneFiles, totalFiles)
        state = UiState.State.IMPORTING
    }

    /**
     * 进度推进：暂停时保持 `PAUSED`；传输视图已关闭时保持当前视图，不抢回传输屏。
     *
     * 只接收文件计数。字节 / 速率参数已移除（issue #70）：#177 证明 `getPartialObject` 的 offset
     * 真机不生效，「已落盘字节」不可信，任何字节级进度都不能上屏。
     */
    fun updateProgress(doneFiles: Int, totalFiles: Int) {
        importDoneFiles = doneFiles
        importTotalFiles = totalFiles
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
        // P-09：回到照片墙后必须重算空间预检，不能只依赖勾选变化。
        callbacks?.onBackToCatalog()
    }

    /**
     * 单格勾选（issue #83）：勾的是一个**照片单元**，同名 ARW + JPG 两个句柄一起写。
     *
     * 这样 filter 切换与导出载荷切换都不会出现「选了 ARW 却导 JPG」的半选状态；
     * 隐藏的已选项保留，由 [visibleUnits] 决定本轮是否计入（`C-10`）。
     */
    fun toggleUnitSelection(unit: PhotoWall.Unit) {
        val sel = selection ?: return
        val on = !unit.isChecked(sel)
        for (handle in unit.handles()) sel.setChecked(handle, on)
        selectionVersion++
        // P-09：计划大小随勾选变化，交给 Main 重新预检空间（决定主 CTA 是否禁用）。
        callbacks?.onSelectionChanged()
    }

    /**
     * 日期组选（issue #62 / #83）：组内有未选 → 全选；已全选 → 全不选。
     *
     * 全选时自动展开（让 V-11 的格子形态变化可见）。只回调一次 `onSelectionChanged`，
     * 空间预检不按张数重复跑；空组直接返回（没有可选对象）。
     */
    fun toggleGroupSelection(group: PhotoWall.Group) {
        val sel = selection ?: return
        if (group.photos.isEmpty()) return
        val handles = group.photos.flatMap { it.handles() }
        val select = !sel.allCheckedHandles(handles)
        sel.setCheckedAll(handles, select)
        selectionVersion++
        if (select) collapsed.remove(group.key)
        callbacks?.onSelectionChanged()
    }

    fun openPhoto(unit: PhotoWall.Unit) {
        openedPhoto = unit
    }

    fun bindThumbnails(store: ThumbnailStore) {
        thumbnails = store
    }

    /** 读网格缩略图（**必须**在 IO 线程调用）；单元内同名 JPG 优先、缺失回退 ARW（`V-32`）。 */
    fun loadThumbnail(unit: PhotoWall.Unit): Bitmap? = thumbnails?.load(unit)

    /** 看图页首帧只读缓存（整张 JPG 缓存优先，其次预览/网格缓存），不触发相机调用。 */
    fun cachedPreview(unit: PhotoWall.Unit): Bitmap? = thumbnails?.cachedPreview(unit)

    /** 读看图页缩略图（**必须**在 IO 线程调用）；null 时保持 U-51 胶囊形态。 */
    fun loadPreview(unit: PhotoWall.Unit): Bitmap? = thumbnails?.loadPreview(unit)

    /** 停留后补同名 JPG 整张（**必须**在 IO 线程调用）；没有 JPG 或取图失败时返回 null。 */
    fun loadFullJpeg(unit: PhotoWall.Unit): Bitmap? = thumbnails?.loadFullJpeg(unit)

    fun closePhoto() {
        openedPhoto = null
    }

    fun toggleCollapsed(key: String) {
        if (!collapsed.remove(key)) collapsed.add(key)
    }

    fun isCollapsed(key: String): Boolean = collapsed.contains(key)

    /**
     * 切 filter（issue #83）：可见集合与导出载荷都会变（`All` 0.86 GB ↔ `RAW` 11.89 GB），
     * 所以必须像勾选变化一样触发空间预检；否则 `lowSpace` 会停在上一档的结论上。
     */
    fun setFilter(kind: FilterKind, date: String? = null) {
        filterKind = kind
        filterDate = date
        callbacks?.onSelectionChanged()
    }

    fun addLog(line: String) {
        val next = (logLines + line).takeLast(200)
        logLines = next
    }

    /** 写入空间预检结果（PD / P-09）；`detail` 只在 `low` 为真时有意义。 */
    fun setSpaceStatus(low: Boolean, detail: String) {
        lowSpace = low
        lowSpaceDetail = if (low) detail else ""
    }

    /** 本轮开始前清空失败明细（PD）。 */
    fun clearFailedLines() {
        failedLines = emptyList()
    }

    /** 写入本轮续传跳过数（issue #53）；`count <= 0` 时清空，不显示假的「跳过 0」。 */
    fun showSkipped(count: Int) {
        skippedLine = com.rea.mtpimport.UiText.skippedLine(count)
    }

    /** 追加一条逐文件失败（PD）；最多保留 20 条，供 Details 抽屉长期展示。 */
    fun addFailedLine(line: String) {
        failedLines = (failedLines + line).takeLast(20)
    }

    /**
     * demo 载荷 → 界面状态。目录态会同时把假树装进来，这样从 `IMPORTING` 取消后
     * 还能回到照片墙继续点选（T-03）。
     */
    fun applyDemo(p: DemoPayload?) {
        if (p == null) return
        demoActive = true
        // demo 不碰真实存储；清掉真实路径留下的预检结果与失败明细，避免假照片墙的 CTA
        // 被误禁用、Details 里残留真实失败。
        setSpaceStatus(false, "")
        clearFailedLines()
        showSkipped(0)
        transferViewClosed = false
        exitConfirmOpen = false
        if (p.catalog != null && catalog == null) {
            // 只在还没有照片墙时才建树：从 READY 切 ERROR 等态必须保留已有 wall / selection，
            // 否则「ERROR 返回照片墙、选择还在」（§1.3）演不出来。
            // `Selection(p.catalog)` 初始是**空集**（`Selection` 构造只存 catalog，`checked` 初始为空），不是全选；
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
