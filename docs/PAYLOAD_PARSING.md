# Payload 解析经验与踩坑记录（Payload Parsing Notes）

本文档记录 VIVO-OTA-Tracker-Android 解析 vivo OTA `payload.zip` 相关功能的实现要点、
已修复的 bug 与实测结论，供后续维护参考。

## 1. 解析流程概览

```
VivoPayloadHttpUtil.init(url)         // HTTP 流式读取，支持 Range
  → PayloadUtil.getPayloadOffset(url) // 在 zip 里定位 payload.bin 偏移
  → PayloadUtil.initPayload(...)      // 读取 CrAU 头 + DeltaArchiveManifest protobuf
  → PayloadUtil.getPartitionInfoList(payload) // 转成 PartitionInfo 列表
```

- `CrAU` 魔数 = `43 72 41 55`，payload.bin 以此为起点。
- 解析层与 UI 层通过 `PayloadDumperUiState` + `PayloadToast` 通信。

## 2. OTA 包类型与错误识别

vivo OTA 链接大致分两类，必须用不同方式处理：

| 类型 | 特征 | 本工具处理 |
|------|------|-----------|
| A/B 增量包 | zip 内含 `payload.bin`（CrAU 头） | ✅ 支持，解析分区列表 |
| recovery 全量包（非 A/B） | zip 内含大量 img 文件、无 `payload.bin`；多为 ZIP64 + DEFLATE | ❌ 仅给出友好错误提示 |

实测链接（2024-11 recovery）：`sysuptxdl.vivo.com.cn/.../20241104...zip`
经 Python 离线分析确认：
- 是 ZIP64 格式（central directory 用 `0xFFFFFFFF` 标记偏移）。
- 221 个条目，全为 DEFLATE 压缩的 img，无 `payload.bin`。
- 因此解析层在 `locateCentralDirectory` 找不到 payload.bin 时抛
  `IOException("NOT_A_PAYLOAD_ZIP")`，UI 层拦截并提示
  "这不是 A/B 增量包（payload.bin 不存在）。该 OTA 包可能是 recovery 全量包，无法用此工具解析。"

**错误标记约定**（解析层抛特定文案，ViewModel 用 `mapErrorMessage` 转中文）：
- `NOT_A_PAYLOAD_ZIP` → recovery 全量包 / 无 payload.bin。
- `NOT_A_VALID_ZIP` → 文件不是有效 zip（下载不完整、链接错误等）。

> 决策：用户选择**只做错误提示，不实现 recovery 包解析**（避免引入完整 recovery 解包逻辑）。

## 3. 已修复的 bug

### Bug 1：ZIP local file header 偏移算错（52747 vs 真实 61）
- **文件**：`VivoPayloadUtil.kt` 的 `locateLocalFileOffset`。
- **原因**：`byteBuffer.getLong()` 读完 LOCSIG 后 position 已前进 4 字节，
  紧接 `byteBuffer.position(byteBuffer.position() + 26)` 会得到绝对偏移 30，
  但 `fileNameLength` 字段实际在**绝对偏移 26**（即相对还需 +22 而非 +26）。
- **修复**：`+ 26` → `+ 22`。实测真实 `dataOffset = 61`（stored 方式，method=0）。

### Bug 2：proto 必填字段缺失导致解析失败
- **文件**：`update_metadata.proto` 的 `InstallOperation`。
- **原因**：部分 VABC 操作的 `type` 字段不存在，原 `required Type type = 1`
  在 AOSP 新 schema 下会报 "missing required fields"。
- **修复**：改为 `optional Type type = 1`；`getPartitionInfoList` 用
  `it.type.name` 兜底（无 type 时取枚举默认名）。

### Bug 3：输入框链接不生效（点了"解析"却用旧地址解析）
- **现象**：在输入框粘贴新的 recovery 链接、点击"解析"，界面仍按初始带入的旧地址
  解析成功，既不报错也无红色卡片。
- **根因**：`VivoPayloadViewModel.submitUrl()` 只递增了 `parseRequestId`，
  没有把输入框内容写入解析目标 `pathOrUrl`；而
  `VivoPayloadDumperScreen` 的 `LaunchedEffect(parseRequestId)` 里调用的是
  `parsePayload(uiState.pathOrUrl)`（始终是初始地址）。
- **修复**：`submitUrl()` 先把输入框内容同步进 `pathOrUrl` 并清空上一轮结果，
  再递增 `parseRequestId`：
  ```kotlin
  fun submitUrl() {
      val target = _uiState.value.inputUrl.trim()
      if (target.isEmpty()) return
      _uiState.value = _uiState.value.copy(
          pathOrUrl = target,
          parseRequestId = _uiState.value.parseRequestId + 1,
          isParsing = false,
          error = null,
          archiveInfo = null,
          partitions = emptyList(),
          filteredPartitions = emptyList(),
          selectedPartitions = emptySet(),
          selectedPartition = null
      )
  }
  ```

### Bug 4：`LaunchedEffect` 无限重解析循环
- **现象**：解析出错 → 分区列表为空 → 触发再次解析 → 死循环。
- **修复**：用 `parseRequestId` 计数器驱动解析（仅在 `parseRequestId > 0` 时触发），
  错误分支只更新 `error`/`isParsing`，不再间接触发新的解析请求。

### Bug 5：提取出来的分区镜像大小不对（2026-10 修复）
- **现象**：分区列表里显示的大小是对的，但「提取」出来的 `.img` 偏小、内容也不对。
  例：`boot` 声明 96 MB，实际只产出 64 MB；`system` 少 20 MB；`vbmeta` 只剩 1.5 KB。
- **根因（多个问题叠加，都在提取路径上）**：

  1. **proto 缺 `REPLACE_ZSTD`**（主因）。AOSP Android 14+ 起新增
     `InstallOperation.Type.REPLACE_ZSTD = 14`，vivo 近期 OTA 包大量使用。
     项目 `update_metadata.proto` 只定义到 `LZ4DIFF_PUFFDIFF = 13`，
     proto2 对**未知枚举值**的处理是「丢弃字段」——`HasField("type")` 变 false、
     `type` 取默认值 `REPLACE = 0`。于是代码把 **zstd 压缩流原样写进镜像**：
     压缩后体积比原始数据小，产出的 `.img` 自然偏小且内容完全错误。
     实测某包 490 个 operation 里有 488 个是 `type=14`。
  2. **只写 `dstExtentsList[0]`**。一个 operation 可以有多个 dst extents
     （增量包实测 915 个这样的 operation，且全部非连续），原实现只 seek 到第一段
     然后连续写，后续 extents 全部丢失。
  3. **ZERO 用 `data_length` 当长度**。这些包里 ZERO 的 `data_length` 是 0，
     原实现 `ByteArray(0)` 等于什么都没写；正确长度是 `num_blocks * blockSize`。
  4. **提前关闭共享 fd**。`FileOutputStream(partOutput.fd).use { ... }` 会关闭
     `RandomAccessFile` 的底层 fd，同一分区的第二条 operation 直接报
     `Bad file descriptor`（多 operation 的分区必然失败）。
  5. 没有按 `new_partition_info.size` 收尾，文件长度取决于最后写入位置。
- **修复**（`VivoPayloadUtil.kt`）：
  - proto 补 `REPLACE_ZSTD = 14`；
  - 引入 `com.github.luben:zstd-jni`（**必须用 `@aar` 变体**，普通 jar 不含
    `jni/*.so`，运行时会因找不到本地库崩溃）；
  - `copyToExtents()` 按 dst extents 顺序流式落位，支持多段、非连续；
  - `writeZeros()` 按 extents 块数补零；
  - 不再包裹 `FileOutputStream(partOutput.fd)`，改用 `RandomAccessFile` 直接写；
  - `extractPartition()` 先 `setLength(0)` 清空残留，结束时 `setLength(declaredSize)`。
- **验证**：拿真实 OTA 包（`sysupdxdl.vivo.com.cn/.../20260717080349...zip`，
  490 ops 中 488 个 `REPLACE_ZSTD`）逐分区比对
  `new_partition_info.size` 与 SHA256：**39/39 全部一致**。
  修复前同一批分区全部 FAIL（压缩流直写 / fd 已关闭）。
- **遗留**：增量包（`SOURCE_COPY` / `BROTLI_BSDIFF` 等）需要源镜像才能重建，
  在线提取仍不支持——这是既有行为，本次未改变。报错信息会落到
  `Log.e("VivoPayload", ...)`，不再是无声失败。

## 4. UI 交互要点

- 顶部：`TextField`（miuix 用 `label` 而非 `placeholder`）+ "解析" 按钮。
- 解析中：`CircularProgressIndicator` + 文案。
- 错误态：红色（`Color(0xFFE53935)`）`Card` 显示 ⚠ + `uiState.error`，同时弹
  `Toast.LENGTH_LONG`。
- 点击分区项 → `selectPartition(name)` 弹出 `PartitionDetailDialog`（底部弹层）：
  显示 size / rawSize / ops 数 / 完整 SHA256 / 各 operation type 统计。
- 多选 → 底部 `Card` 显示"已选 N 项" + "提取选中"按钮。

## 5. 离线分析脚本（根目录）

- `probe_zip.py`：确认 A/B 包 payload 真实偏移（=61，stored）。
- `probe_zip2.py` / `probe_zip2_zip64.py`：确认 2024 recovery 包是 ZIP64 + DEFLATE、
  无 payload.bin。
  用 Python 的 `zipfile` 离线读 central directory，比在设备上反复构建高效。

## 6. 调试日志保留策略

按用户要求保留解析关键节点的 `Log.i("VivoPayload", ...)`：
`parseFromUrl: start / http init done / payloadOffset / initPayload done /
getPartitionInfoList done`，以及 `getPayloadOffset` 内对探测偏移处 32 字节、
前 64 字节的 hex dump（`Log.d` 级别，避免日志过噪）。
真机定位时：`adb logcat | grep VivoPayload`。
