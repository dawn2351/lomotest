package com.lomo.data.repository.importnotes

import android.content.Context
import android.net.Uri
import com.lomo.domain.model.DraftId
import com.lomo.domain.model.ExternalNotesImportSummary
import com.lomo.domain.model.ExternalNotesSource
import com.lomo.domain.model.MemoCreateAttempt
import com.lomo.domain.model.MemoOperationId
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.ExternalNotesImportRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.WorkspaceMutationLease
import com.lomo.domain.usecase.CreateMemoUseCase
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipFile

internal class ExternalNotesImportRepositoryImpl(
    private val context: Context,
    private val dispatcher: DispatcherProvider,
    private val validator: ValidateMemoContentUseCase,
    private val lease: WorkspaceMutationLease,
    private val createMemo: CreateMemoUseCase,
    private val media: MediaRepository,
) : ExternalNotesImportRepository {
    override suspend fun importNotes(
        source: ExternalNotesSource,
        openInput: () -> InputStream?,
    ): ExternalNotesImportSummary = withContext(dispatcher.io) {
        BufferedInputStream(requireNotNull(openInput()) { "Unable to open import file" }).use { input ->
            when (source) {
                ExternalNotesSource.FLOMO -> importFlomo(input)
                ExternalNotesSource.WE_READ -> merge(source, parseWeReadExport(input.readImportText()))
            }
        }
    }

    private suspend fun importFlomo(input: BufferedInputStream): ExternalNotesImportSummary {
        val source = ExternalNotesSource.FLOMO
        val zone = ZoneId.systemDefault()
        input.mark(ZIP_HEADER.size)
        val isZip = ZIP_HEADER.all { expected -> input.read() == expected.toInt() }
        input.reset()
        if (!isZip) return merge(source, parseFlomoExport(input.readImportText(), zone))

        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "flomo-import-")
        val file = directory.resolve("export.zip")
        try {
            Files.newOutputStream(file).use { output ->
                input.copyBounded(output, MAX_ARCHIVE_BYTES, "Flomo ZIP exceeds the 128 MiB import limit")
            }
            return ZipFile(file.toFile()).use { zip ->
                val html = zip.entries().asSequence().filter {
                    !it.isDirectory && it.name.endsWith(".html", ignoreCase = true) &&
                        !it.name.startsWith("__MACOSX/")
                }.singleOrNull()
                requireNotNull(html) { "Select a flomo ZIP containing one HTML export" }
                val archive = FlomoArchive(zip, html.name, directory)
                val text = zip.getInputStream(html).use { it.readImportText() }
                merge(source, parseFlomoExport(text, zone), archive)
            }
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    private suspend fun merge(
        source: ExternalNotesSource,
        parsed: List<ExternalNote>,
        archive: FlomoArchive? = null,
    ): ExternalNotesImportSummary {
        val notes = parsed.distinct()
        notes.filter { it.body.isNotBlank() }.forEach { validator.requireValidForCreate(it.body) }
        return lease.withWrite {
            var handled = 0
            var omitted = 0
            for (note in notes) {
                val (saved, omittedCount) = importNote(source, note, archive)
                if (saved) handled += 1
                omitted += omittedCount
            }
            require(handled > 0) { "No usable notes were found in this export" }
            ExternalNotesImportSummary(handled, omitted)
        }
    }

    private suspend fun importNote(
        source: ExternalNotesSource,
        note: ExternalNote,
        archive: FlomoArchive?,
    ): Pair<Boolean, Int> {
        val draft = DraftId(externalNoteOperationId(source, note).value)
        val images = mutableListOf<String>()
        try {
            for (reference in note.imageReferences) {
                val path = archive?.extractImage(reference) ?: continue
                try {
                    images += media.importImage(StorageLocation(path.toString()), draft).raw
                } finally {
                    Files.deleteIfExists(path)
                }
            }
            val missing = note.omittedAttachmentCount + note.imageReferences.size - images.size
            val markdown = images.distinct().joinToString("\n") { "![image](<" + it + ">)" }
            val body = listOf(note.body, markdown).filter(String::isNotBlank).joinToString("\n\n")
            if (body.isBlank()) return false to missing
            val operation = externalNoteOperationId(source, note.copy(body = body))
            createMemo(MemoCreateAttempt(operation, draft, body, note.timestampMillis)) {}
            return true to missing
        } catch (failure: Exception) {
            if (archive != null && note.imageReferences.isNotEmpty()) {
                withContext(NonCancellable) {
                    try {
                        media.releaseDraftLeases(draft)
                    } catch (cleanup: Exception) {
                        failure.addSuppressed(cleanup)
                    }
                }
            }
            throw failure
        }
    }
}

private class FlomoArchive(private val zip: ZipFile, htmlName: String, private val temporary: Path) {
    private val htmlDirectory = htmlName.substringBeforeLast('/', missingDelimiterValue = "")
    private var imageBytesRemaining = MAX_ARCHIVE_BYTES

    fun extractImage(source: String): Path? {
        val relative = Uri.decode(source).removePrefix("./")
        if (relative.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
            relative.any { it == '\\' || it == ':' || it == '\u0000' }
        ) {
            return null
        }
        val extension = relative.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (extension !in imageExtensions) return null
        val name = if (htmlDirectory.isEmpty()) relative else "$htmlDirectory/$relative"
        val entry = zip.getEntry(name) ?: return null
        if (entry.isDirectory || entry.size !in 1L..MAX_IMAGE_BYTES.toLong()) return null
        require(entry.size <= imageBytesRemaining) { "Flomo attachments exceed the 128 MiB import limit" }
        val stem = UUID.nameUUIDFromBytes(name.toByteArray())
        val image = temporary.resolve("flomo-$stem.$extension")
        try {
            zip.getInputStream(entry).use { input ->
                Files.newOutputStream(image).use { output ->
                    input.copyBounded(output, entry.size.toInt(), "Invalid flomo image entry size")
                }
            }
            imageBytesRemaining -= entry.size.toInt()
            return image
        } catch (failure: Exception) {
            Files.deleteIfExists(image)
            throw failure
        }
    }
}

private fun externalNoteOperationId(source: ExternalNotesSource, note: ExternalNote): MemoOperationId {
    val fingerprint = listOf(source.name, note.timestampMillis.toString(), note.body)
        .plus(note.imageReferences).joinToString("\u0000")
    return MemoOperationId("external-import:" + UUID.nameUUIDFromBytes(fingerprint.toByteArray()))
}

private fun InputStream.readImportText(): String {
    val text = ByteArrayOutputStream()
    copyBounded(text, MAX_TEXT_BYTES, "Export text exceeds the 16 MiB import limit")
    return text.toByteArray().decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF")
}

private fun InputStream.copyBounded(output: OutputStream, byteLimit: Int, limitMessage: String) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = byteLimit
    while (true) {
        val read = read(buffer)
        if (read == -1) return
        remaining -= read
        require(remaining >= 0) { limitMessage }
        output.write(buffer, 0, read)
    }
}

private val ZIP_HEADER = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "avif", "heic", "bmp")
private const val MAX_TEXT_BYTES = 16 * 1024 * 1024
private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
private const val MAX_ARCHIVE_BYTES = 128 * 1024 * 1024
