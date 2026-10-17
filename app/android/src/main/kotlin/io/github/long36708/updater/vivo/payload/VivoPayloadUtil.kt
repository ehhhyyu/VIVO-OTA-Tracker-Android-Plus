package io.github.long36708.updater.vivo.payload

import android.util.Log
import chromeos_update_engine.UpdateMetadata
import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

object PayloadUtil {

    private const val MAGIC_VALUE = "CrAU"
    private const val FORMAT_VERSION = 2L

    private val mutex by lazy { Mutex() }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    /**
     * 严格读满 [size] 字节，读不到即视为数据截断（ADR-004）。
     * 半截 manifest 会让 protobuf 解析进入不可预期状态，宁可明确报错。
     */
    private suspend fun readFully(source: ZipByteSource, offset: Long, size: Int): ByteArray {
        if (size < 0) throw IOException("Invalid read size")
        val out = ByteArray(size)
        var got = 0
        while (got < size) {
            val chunk = out.copyOfRange(got, size)
            val read = source.readAt(offset + got, chunk)
            if (read <= 0) break
            System.arraycopy(chunk, 0, out, got, read)
            got += read
        }
        if (got < size) throw IOException("TRUNCATED_READ")
        return out
    }

    /**
     * 从 zip 源解析 payload.bin（ADR-004 D1/D2/D3/D8）。
     *
     * zip 结构解析全部委托 [VivoZipBrowser]（含 ZIP64 extra、扫描式 CENSIG），
     * 这里只负责「payload.bin 是 STORED」这一前提校验与 CrAU/manifest 解析。
     */
    suspend fun initPayload(fileName: String, source: ZipByteSource): Payload {
        val entries = VivoZipBrowser.listZipEntries(source)
        val entry = VivoZipBrowser.findPayloadEntry(entries)
            ?: throw IOException("NOT_A_PAYLOAD_ZIP")
        // D8：远程无法随机解压，非 STORED 必须明确报错，而不是让后面撞上 Invalid magic value
        if (!entry.isStored) throw IOException("PAYLOAD_NOT_STORED")

        val payloadDataOffset = VivoZipBrowser.readEntryDataOffset(source, entry)
        Log.i(
            "VivoPayload",
            "initPayload: entry='${entry.name}' offset=$payloadDataOffset " +
                "size=${entry.uncompressedSize} method=${entry.method} total=${source.size}"
        )

        val payloadSource = SubrangeByteSource(source, payloadDataOffset, entry.uncompressedSize)

        val header = readFully(payloadSource, 0, 24)
        if (String(header, 0, 4, StandardCharsets.UTF_8) != MAGIC_VALUE) {
            throw IOException("Invalid magic value")
        }

        val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        buf.position(4)
        val fileFormatVersion = buf.long
        if (fileFormatVersion != FORMAT_VERSION) {
            throw IOException("Unsupported file format version")
        }
        val manifestSize = buf.long
        val metadataSignatureSize = buf.int

        if (manifestSize <= 0 || manifestSize > payloadSource.size) {
            throw IOException("Invalid manifest size: $manifestSize")
        }
        if (metadataSignatureSize < 0 || metadataSignatureSize > payloadSource.size) {
            throw IOException("Invalid metadata signature size: $metadataSignatureSize")
        }

        val manifest = readFully(payloadSource, 24, manifestSize.toInt())
        if (metadataSignatureSize > 0) {
            readFully(payloadSource, 24 + manifestSize, metadataSignatureSize)
        }

        val deltaArchiveManifest = UpdateMetadata.DeltaArchiveManifest.parseFrom(manifest)
        val payloadHeader = PayloadHeader(fileFormatVersion, manifestSize, metadataSignatureSize)
        // 分区提取仍按「绝对文件偏移」seek（extractFromHttp 用 payload.dataOffset + op.dataOffset），
        // 因此这里要把 payload.bin 在 zip 中的绝对起点加回去。
        val dataOffset = payloadDataOffset + 24 + manifestSize + metadataSignatureSize
        return Payload(
            fileName,
            payloadHeader,
            deltaArchiveManifest,
            dataOffset,
            deltaArchiveManifest.blockSize,
            source.size,
            false
        )
    }

    fun getPartitionInfoList(payload: Payload): List<PartitionInfo> {
        val blockSize = payload.deltaArchiveManifest.blockSize
        val srcCount = payload.deltaArchiveManifest.partitionsList.size
        Log.i("VivoPayload", "getPartitionInfoList: input partitions=$srcCount, blockSize=$blockSize")
        return payload.deltaArchiveManifest.partitionsList.map { partition ->
            // 真实镜像大小：所有 dst extents 占用的块数 * blockSize。
            // 某些分区（纯 ZERO / 无数据）operationsList 或 dstExtentsList 可能为空，需做保护。
            val rawSize = partition.operationsList
                .flatMap { it.dstExtentsList }
                .maxOfOrNull { it.startBlock + it.numBlocks }
                ?.let { it * blockSize }
                ?.takeIf { it > 0L }
                ?: partition.newPartitionInfo.size

            val typeStats = partition.operationsList
                .groupingBy { it.type.name }
                .eachCount()

            PartitionInfo(
                partitionName = partition.partitionName,
                size = partition.newPartitionInfo.size,
                rawSize = rawSize,
                sha256 = partition.newPartitionInfo.hash.toByteArray().toHexString(),
                operationsCount = partition.operationsList.size,
                mergeOperationsCount = partition.mergeOperationsList.size,
                typeStats = typeStats
            )
        }
    }

    /**
     * 读取一条 operation 的压缩/原始数据。
     *
     * 严格读满：半截数据会让解压器产出截断镜像，且不会有任何报错，
     * 最终表现为「提取出来的分区大小不对」。
     */
    private suspend fun readOpData(
        httpUtil: VivoPayloadHttpUtil,
        offset: Long,
        op: UpdateMetadata.InstallOperation,
    ): ByteArray {
        val length = op.dataLength
        if (length < 0 || length > Int.MAX_VALUE) {
            throw IOException("Invalid operation data length: $length")
        }
        val data = ByteArray(length.toInt())
        if (data.isEmpty()) return data
        httpUtil.seek(offset + op.dataOffset)
        val read = httpUtil.readSync(data)
        if (read < data.size) throw IOException("TRUNCATED_READ")
        return data
    }

    /**
     * 把一条 operation 的数据按 dst_extents 顺序落位。
     *
     * 一个 operation 可以有多个 dst_extents（增量包常见），数据是按 extents
     * 顺序连续排布的——只写第一段会丢掉后续块，这正是分区大小/内容不对的另一半原因。
     *
     * 数据短于 extents 声明时允许（尾部由最终 setLength 补零）；
     * 长于声明说明 manifest 与实际数据不一致，直接报错而不是静默丢弃。
     */
    private fun copyToExtents(
        input: InputStream,
        extents: List<UpdateMetadata.Extent>,
        partOutput: RandomAccessFile,
        blockSize: Int,
    ) {
        if (extents.isEmpty()) throw IOException("OPERATION_HAS_NO_EXTENTS")
        var index = 0
        var remainingInExtent = extents[0].numBlocks * blockSize
        partOutput.seek(extents[0].startBlock * blockSize)

        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            var offset = 0
            while (offset < read) {
                while (remainingInExtent <= 0L) {
                    index++
                    if (index >= extents.size) throw IOException("OPERATION_DATA_EXCEEDS_EXTENTS")
                    partOutput.seek(extents[index].startBlock * blockSize)
                    remainingInExtent = extents[index].numBlocks * blockSize
                }
                val chunk = minOf((read - offset).toLong(), remainingInExtent).toInt()
                partOutput.write(buffer, offset, chunk)
                offset += chunk
                remainingInExtent -= chunk
            }
        }
        // 数据短于 extents 声明：剩余块按 AOSP 语义应当为 0。
        // 文件由 extractPartition 以 setLength(0) 新建，这些位置本就是零，
        // 但把差额显式记下来，避免 manifest 与数据不一致时无声无息。
        val leftover = remainingInExtent + extents.drop(index + 1).sumOf { it.numBlocks * blockSize }
        if (leftover > 0L) {
            Log.w(
                "VivoPayload",
                "copyToExtents: data shorter than extents by $leftover bytes (left as zero)"
            )
        }
    }

    /** ZERO 操作按 dst_extents 的块数补零；data_length 在这些包里是 0，不能拿来当长度。 */
    private fun writeZeros(
        extents: List<UpdateMetadata.Extent>,
        partOutput: RandomAccessFile,
        blockSize: Int,
    ) {
        val zeros = ByteArray(64 * 1024)
        for (extent in extents) {
            var remaining = extent.numBlocks * blockSize
            partOutput.seek(extent.startBlock * blockSize)
            while (remaining > 0L) {
                val chunk = minOf(remaining, zeros.size.toLong()).toInt()
                partOutput.write(zeros, 0, chunk)
                remaining -= chunk
            }
        }
    }

    private suspend fun extractFromHttp(
        op: UpdateMetadata.InstallOperation,
        partOutput: RandomAccessFile,
        httpUtil: VivoPayloadHttpUtil,
        blockSize: Int,
        offset: Long,
    ): Long {
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                when (op.type) {
                    UpdateMetadata.InstallOperation.Type.ZERO -> {
                        writeZeros(op.dstExtentsList, partOutput, blockSize)
                    }
                    UpdateMetadata.InstallOperation.Type.REPLACE -> {
                        val data = readOpData(httpUtil, offset, op)
                        copyToExtents(data.inputStream(), op.dstExtentsList, partOutput, blockSize)
                    }
                    UpdateMetadata.InstallOperation.Type.REPLACE_XZ -> {
                        val data = readOpData(httpUtil, offset, op)
                        XZCompressorInputStream(data.inputStream().buffered()).use { input ->
                            copyToExtents(input, op.dstExtentsList, partOutput, blockSize)
                        }
                    }
                    UpdateMetadata.InstallOperation.Type.REPLACE_BZ -> {
                        val data = readOpData(httpUtil, offset, op)
                        BZip2CompressorInputStream(BufferedInputStream(data.inputStream().buffered())).use { input ->
                            copyToExtents(input, op.dstExtentsList, partOutput, blockSize)
                        }
                    }
                    UpdateMetadata.InstallOperation.Type.REPLACE_ZSTD -> {
                        val data = readOpData(httpUtil, offset, op)
                        ZstdInputStreamNoFinalizer(data.inputStream().buffered()).use { input ->
                            copyToExtents(input, op.dstExtentsList, partOutput, blockSize)
                        }
                    }
                    else -> throw RuntimeException("Unsupported operation type ${op.type}")
                }
                // 进度用「写到的最高字节位置」，而不是最后一段 extents 的末端
                op.dstExtentsList.maxOfOrNull { it.startBlock * blockSize + it.numBlocks * blockSize } ?: 0L
            }
        }
    }

    suspend fun extractPartition(
        metadataPartition: UpdateMetadata.PartitionUpdate,
        input: Any,
        outputDir: String,
        payload: Payload,
        onProgressUpdate: (Long) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val downloadDir = Paths.get(outputDir)
            if (!Files.exists(downloadDir)) {
                Files.createDirectories(downloadDir)
            }

            RandomAccessFile(
                "$outputDir/${metadataPartition.partitionName}.img",
                "rw"
            ).use { partOutput ->
                // 复用上一次失败留下的临时文件会读到旧内容，先清空
                partOutput.setLength(0)
                val httpUtil = input as? VivoPayloadHttpUtil
                    ?: throw IllegalArgumentException("Only online URL is supported")
                var writtenEnd = 0L
                metadataPartition.operationsList.forEach { operation ->
                    writtenEnd = maxOf(
                        writtenEnd,
                        extractFromHttp(
                            operation,
                            partOutput,
                            httpUtil,
                            payload.blockSize,
                            payload.dataOffset
                        )
                    )
                    onProgressUpdate(writtenEnd)
                }
                // 镜像必须是完整大小：尾部未被任何 operation 覆盖的块补零，
                // 否则产出文件会比 manifest 声明的 new_partition_info.size 短。
                val declaredSize = metadataPartition.newPartitionInfo.size
                if (declaredSize > 0) {
                    partOutput.setLength(declaredSize)
                }
            }
        }
    }

}
