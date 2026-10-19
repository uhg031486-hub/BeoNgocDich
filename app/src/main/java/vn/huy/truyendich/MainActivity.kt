package vn.huy.truyendich

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import vn.huy.truyendich.databinding.ActivityMainBinding
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: SettingsRepository
    private var selectedUri: Uri? = null
    private var selectedName = ""
    @Volatile private var cancelled = false
    @Volatile private var pauseRequested = false
    private var activeWork: Future<*>? = null
    private var pendingOutput: TranslationOutput? = null
    private var pendingServerEpub: RemoteEpub? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        selectedUri = uri
        selectedName = displayName(uri)
        binding.fileNameText.text = selectedName
        binding.translateButton.text = if (selectedName.endsWith(".txt", true)) {
            "Bắt đầu dịch trực tiếp (TXT)"
        } else {
            "Dịch hàng loạt và tạo EPUB"
        }
        binding.statusText.text = "Đã chọn $selectedName"
    }

    private val outputCreator = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val output = pendingOutput
        pendingOutput = null
        if (uri == null || output == null) {
            output?.file?.delete()
            return@registerForActivityResult
        }
        try {
            contentResolver.openOutputStream(uri)?.use { stream ->
                output.file.inputStream().use { input -> input.copyTo(stream) }
            } ?: throw IllegalStateException("Không thể ghi tệp đầu ra.")
            binding.statusText.text = "Đã lưu ${output.suggestedName}"
            Toast.makeText(this, "Đã lưu bản dịch.", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            binding.statusText.text = "Không thể lưu tệp: ${error.message}"
            Toast.makeText(this, "Không thể lưu tệp dịch.", Toast.LENGTH_LONG).show()
        } finally {
            output.file.delete()
        }
    }

    private val serverEpubCreator = registerForActivityResult(ActivityResultContracts.CreateDocument("application/epub+zip")) { uri ->
        val epub = pendingServerEpub
        pendingServerEpub = null
        if (uri == null || epub == null) return@registerForActivityResult
        val settings = repository.load()
        Executors.newSingleThreadExecutor().execute {
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    ServerEpubRepository(this, settings).download(epub.id, output)
                } ?: error("Không thể mở nơi lưu EPUB.")
                runOnUiThread { binding.serverEpubText.text = "Đã tải ${epub.name} từ server." }
            } catch (error: Exception) {
                runOnUiThread { binding.serverEpubText.text = "Không tải được EPUB: ${error.message}" }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = SettingsRepository(this)
        setAdapters()
        populate(repository.load())
        setupActions()
    }

    override fun onDestroy() {
        cancelled = true
        activeWork?.cancel(true)
        pendingOutput?.file?.delete()
        super.onDestroy()
    }

    private fun setAdapters() {
        binding.modelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            TranslationCatalog.models.map { it.label }
        )
        binding.genreSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            TranslationCatalog.genres
        )
    }

    private fun populate(settings: TranslationSettings) {
        binding.routerEndpointInput.setText(settings.endpoint)
        binding.concurrencyInput.setText(settings.concurrency.toString())
        binding.chapterStartInput.setText(settings.chapterStart.toString())
        binding.chapterEndInput.setText(if (settings.chapterEnd > 0) settings.chapterEnd.toString() else "")
        binding.styleNotesInput.setText(settings.styleNotes)
        binding.glossaryInput.setText(settings.glossary)
        binding.syncEnabledCheck.isChecked = settings.syncEnabled
        binding.tailscaleUrlInput.setText(settings.tailscaleUrl)
        binding.funnelUrlInput.setText(settings.funnelUrl)
        binding.vbookBookIdInput.setText(settings.vbookBookId)
        binding.vbookSourceInput.setText(settings.vbookSource)
        binding.modelSpinner.setSelection(TranslationCatalog.models.indexOfFirst { it.id == settings.model }.coerceAtLeast(0))
        binding.genreSpinner.setSelection(TranslationCatalog.genres.indexOf(settings.genre).coerceAtLeast(0))
    }

    private fun setupActions() {
        binding.selectFileButton.setOnClickListener {
            filePicker.launch(arrayOf("application/epub+zip", "application/zip", "text/plain", "text/*", "application/octet-stream"))
        }
        binding.advancedButton.setOnClickListener {
            binding.advancedPanel.isVisible = !binding.advancedPanel.isVisible
            binding.advancedButton.text = if (binding.advancedPanel.isVisible) "Ẩn cài đặt nâng cao" else "Cài đặt nâng cao"
        }
        binding.translateButton.setOnClickListener { startTranslation() }
        binding.serverEpubButton.setOnClickListener { showServerEpubs() }
        binding.uploadServerButton.setOnClickListener { uploadEpubToServer() }
        binding.cancelButton.setOnClickListener {
            cancelled = true
            activeWork?.cancel(true)
            binding.statusText.text = "Đang hủy tác vụ..."
        }
        binding.pauseExportButton.setOnClickListener {
            pauseRequested = true
            binding.pauseExportButton.isEnabled = false
            binding.statusText.text = "Đang hoàn tất các chương đang chạy, sau đó xuất EPUB tạm..."
        }
    }

    private fun uploadEpubToServer() {
        val uri = selectedUri ?: run { toast("Hãy chọn tệp trước."); return }
        if (selectedName.endsWith(".txt", true)) {
            AlertDialog.Builder(this)
                .setTitle("Thông báo")
                .setMessage("Server hiện tại chỉ hỗ trợ dịch nền định dạng EPUB. Với file TXT, vui lòng bấm 'Bắt đầu dịch trực tiếp' trên app.")
                .setPositiveButton("Đã hiểu", null)
                .show()
            binding.serverEpubText.text = "Server hiện tại chỉ hỗ trợ dịch nền định dạng EPUB. Với file TXT, vui lòng bấm 'Bắt đầu dịch trực tiếp' trên app."
            return
        }
        if (!selectedName.endsWith(".epub", true)) { toast("Dịch nền trên server hiện chỉ nhận EPUB."); return }
        val settings = settingsFromForm() ?: return
        val size = contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: -1L
        if (size <= 0L) { toast("Không xác định được kích thước EPUB."); return }
        repository.save(settings)
        binding.uploadServerButton.isEnabled = false
        binding.serverEpubText.text = "Đang đẩy EPUB lên server..."
        Executors.newSingleThreadExecutor().execute {
            try {
                val job = contentResolver.openInputStream(uri)?.use { input ->
                    ServerEpubRepository(this, settings).upload(selectedName, settings.model, input, size)
                } ?: error("Không mở được EPUB.")
                runOnUiThread {
                    binding.uploadServerButton.isEnabled = true
                    binding.serverEpubText.text = "Server đang dịch: 0/${job.total} chương. Bấm 'Xem EPUB' để tải bản tạm khi cần đọc."
                }
            } catch (error: Exception) {
                runOnUiThread { binding.uploadServerButton.isEnabled = true; binding.serverEpubText.text = "Không đẩy được EPUB: ${error.message}" }
            }
        }
    }

    private fun showServerEpubs() {
        val settings = settingsFromForm() ?: return
        repository.save(settings)
        binding.serverEpubButton.isEnabled = false
        binding.serverEpubText.text = "Đang lấy danh sách EPUB từ server..."
        Executors.newSingleThreadExecutor().execute {
            try {
                val books = ServerEpubRepository(this, settings).list()
                runOnUiThread {
                    binding.serverEpubButton.isEnabled = true
                    if (books.isEmpty()) {
                        binding.serverEpubText.text = "Server chưa có EPUB dịch hoàn tất."
                        return@runOnUiThread
                    }
                    binding.serverEpubText.text = "Có ${books.size} EPUB trên server. Chọn một file để tải."
                    AlertDialog.Builder(this)
                        .setTitle("EPUB trên server")
                        .setItems(books.map { "${it.name} (${it.completed}/${it.total} chương)" }.toTypedArray()) { _, index ->
                            pendingServerEpub = books[index]
                            serverEpubCreator.launch(books[index].name.removeSuffix(".epub") + ".vi.epub")
                        }.show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    binding.serverEpubButton.isEnabled = true
                    binding.serverEpubText.text = "Không xem được kho EPUB: ${error.message}"
                }
            }
        }
    }

    private fun startTranslation() {
        if (activeWork != null && !activeWork!!.isDone) return
        val uri = selectedUri ?: run {
            toast("Hãy chọn tệp EPUB hoặc TXT trước.")
            return
        }
        val settings = settingsFromForm() ?: return
        repository.save(settings)
        cancelled = false
        pauseRequested = false
        setBusy(true)
        binding.statusText.text = "Đang chuẩn bị dịch hàng loạt tối đa ${settings.concurrency} chương cùng lúc..."
        activeWork = Executors.newSingleThreadExecutor().submit {
            try {
                val output = BookTranslator(this).translate(
                    uri,
                    selectedName,
                    settings,
                    { cancelled || Thread.currentThread().isInterrupted },
                    { pauseRequested },
                    ::publishProgress
                )
                runOnUiThread {
                    setBusy(false)
                    pendingOutput = output
                    binding.statusText.text = if (output.paused) {
                        "Đã tạm dừng. Đã dịch ${output.completedChapters}/${output.totalChapters} chương trong phạm vi chọn; đang mở chỗ lưu EPUB tạm."
                    } else if (output.isComplete) {
                        "Dịch xong. Chọn nơi lưu ${output.suggestedName}."
                    } else {
                        "Mạng bị gián đoạn: đã dịch ${output.completedChapters}/${output.totalChapters} chương. Lưu phần tạm; chọn lại đúng file để dịch tiếp."
                    }
                    outputCreator.launch(output.suggestedName)
                }
            } catch (_: CancellationException) {
                runOnUiThread {
                    setBusy(false)
                    binding.statusText.text = "Đã hủy tác vụ. Bản dịch đã đồng bộ trước đó vẫn được giữ trên server."
                }
            } catch (error: Exception) {
                runOnUiThread {
                    setBusy(false)
                    binding.statusText.text = "Dịch thất bại: ${error.message ?: "lỗi không xác định"}"
                    toast("Không thể hoàn tất dịch. Xem trạng thái để biết chi tiết.")
                }
            }
        }
    }

    private fun settingsFromForm(): TranslationSettings? {
        val endpoint = binding.routerEndpointInput.text.toString().trim()
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            binding.routerEndpointInput.error = "Endpoint phải bắt đầu bằng http:// hoặc https://"
            binding.routerEndpointInput.requestFocus()
            return null
        }
        val threads = binding.concurrencyInput.text.toString().toIntOrNull()
        if (threads == null || threads !in 1..50) {
            binding.concurrencyInput.error = "Chọn từ 1 đến 50 luồng"
            binding.concurrencyInput.requestFocus()
            return null
        }
        val start = binding.chapterStartInput.text.toString().trim().ifBlank { "1" }.toIntOrNull()
        if (start == null || start < 1) {
            binding.chapterStartInput.error = "Chương đầu phải từ 1"
            binding.chapterStartInput.requestFocus()
            return null
        }
        val endText = binding.chapterEndInput.text.toString().trim()
        val end = if (endText.isBlank()) 0 else endText.toIntOrNull()
        if (end == null || end < 0 || (end > 0 && end < start)) {
            binding.chapterEndInput.error = "Chương cuối phải lớn hơn hoặc bằng chương đầu"
            binding.chapterEndInput.requestFocus()
            return null
        }
        return TranslationSettings(
            apiKey = TranslationCatalog.EMBEDDED_ROUTER_KEY,
            endpoint = endpoint,
            model = TranslationCatalog.models[binding.modelSpinner.selectedItemPosition].id,
            charsPerRequest = 6_000,
            concurrency = threads,
            genre = TranslationCatalog.genres[binding.genreSpinner.selectedItemPosition],
            styleNotes = binding.styleNotesInput.text.toString(),
            glossary = binding.glossaryInput.text.toString(),
            syncEnabled = binding.syncEnabledCheck.isChecked,
            tailscaleUrl = binding.tailscaleUrlInput.text.toString().trim().ifBlank { TranslationCatalog.DEFAULT_TAILSCALE_URL },
            funnelUrl = binding.funnelUrlInput.text.toString().trim().ifBlank { TranslationCatalog.DEFAULT_FUNNEL_URL },
            serverKey = TranslationCatalog.EMBEDDED_SERVER_KEY,
            vbookBookId = binding.vbookBookIdInput.text.toString(),
            vbookSource = binding.vbookSourceInput.text.toString(),
            chapterStart = start,
            chapterEnd = end
        )
    }

    private fun publishProgress(done: Int, total: Int, status: String) = runOnUiThread {
        binding.progressBar.progress = if (total == 0) 0 else (done * 100 / total)
        binding.statusText.text = status
    }

    private fun setBusy(busy: Boolean) {
        binding.translateButton.isEnabled = !busy
        binding.selectFileButton.isEnabled = !busy
        binding.cancelButton.visibility = if (busy) View.VISIBLE else View.GONE
        binding.pauseExportButton.visibility = if (busy) View.VISIBLE else View.GONE
        binding.pauseExportButton.isEnabled = busy && !pauseRequested
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        if (!busy) activeWork = null
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "truyen.epub"
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
