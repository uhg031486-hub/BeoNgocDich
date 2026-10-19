package vn.huy.truyendich

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
        AppUpdateManager.checkForUpdate(this)

        TranslationNotificationManager.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    override fun onDestroy() {
        cancelled = true
        activeWork?.cancel(true)
        pendingOutput?.file?.delete()
        TranslationNotificationManager.cancel(this)
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
        binding.crawlWebButton.setOnClickListener { showCrawlWebDialog() }
        binding.glossaryButton.setOnClickListener { showGlossaryDialog() }
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
        val isTxt = selectedName.endsWith(".txt", true)
        val isEpub = selectedName.endsWith(".epub", true)
        if (!isTxt && !isEpub) {
            toast("Dịch nền trên server hỗ trợ file EPUB hoặc TXT.")
            return
        }
        val settings = settingsFromForm() ?: return
        repository.save(settings)
        binding.uploadServerButton.isEnabled = false
        binding.serverEpubText.text = if (isTxt) "Đang đóng gói TXT thành EPUB để gửi lên server..." else "Đang đẩy EPUB lên server..."
        Executors.newSingleThreadExecutor().execute {
            var tempEpubFile: java.io.File? = null
            try {
                val (uploadName, uploadInput, uploadSize) = if (isTxt) {
                    val baseName = selectedName.removeSuffix(".txt").removeSuffix(".TXT")
                    val temp = java.io.File(cacheDir, "${baseName}_${System.currentTimeMillis()}.epub")
                    tempEpubFile = temp
                    contentResolver.openInputStream(uri)?.use { inStream ->
                        TxtToEpub.convert(inStream, baseName, temp)
                    } ?: error("Không thể đọc tệp TXT.")
                    Triple("$baseName.epub", java.io.FileInputStream(temp), temp.length())
                } else {
                    val size = contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: -1L
                    if (size <= 0L) error("Không xác định được kích thước EPUB.")
                    val inStream = contentResolver.openInputStream(uri) ?: error("Không mở được EPUB.")
                    Triple(selectedName, inStream, size)
                }

                val job = uploadInput.use { input ->
                    ServerEpubRepository(this, settings).upload(uploadName, settings.model, input, uploadSize)
                }
                runOnUiThread {
                    binding.uploadServerButton.isEnabled = true
                    binding.serverEpubText.text = "Server đang dịch: 0/${job.total} chương/phần. Bấm 'Xem EPUB' để tải bản tạm khi cần đọc."
                }
            } catch (error: Exception) {
                runOnUiThread {
                    binding.uploadServerButton.isEnabled = true
                    binding.serverEpubText.text = "Không đẩy được tệp lên server: ${error.message}"
                }
            } finally {
                tempEpubFile?.delete()
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
                    if (output.isComplete) {
                        TranslationNotificationManager.showCompleted(this@MainActivity, selectedName, output.totalChapters)
                    } else if (output.paused) {
                        TranslationNotificationManager.cancel(this@MainActivity)
                    }
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
                    TranslationNotificationManager.cancel(this@MainActivity)
                    binding.statusText.text = "Đã hủy tác vụ. Bản dịch đã đồng bộ trước đó vẫn được giữ trên server."
                }
            } catch (error: Exception) {
                runOnUiThread {
                    setBusy(false)
                    TranslationNotificationManager.showError(this@MainActivity, selectedName, error.message ?: "lỗi không xác định")
                    binding.statusText.text = "Dịch thất bại: ${error.message ?: "lỗi không xác định"}"
                    toast("Không thể hoàn tất dịch. Xem trạng thái để biết chi tiết.")
                }
            }
        }
    }

    private fun showCrawlWebDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_crawl_web, null)
        val urlInput = dialogView.findViewById<EditText>(R.id.webUrlInput)
        val limitInput = dialogView.findViewById<EditText>(R.id.chapterLimitInput)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.crawlProgressBar)
        val statusText = dialogView.findViewById<TextView>(R.id.crawlStatusText)
        val startBtn = dialogView.findViewById<Button>(R.id.startCrawlButton)
        val cancelBtn = dialogView.findViewById<Button>(R.id.cancelCrawlButton)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        var crawlCancelled = false

        startBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                urlInput.error = "Vui lòng nhập link hợp lệ (http:// hoặc https://)"
                return@setOnClickListener
            }
            val limit = limitInput.text.toString().toIntOrNull() ?: 30

            startBtn.isEnabled = false
            urlInput.isEnabled = false
            limitInput.isEnabled = false
            cancelBtn.text = "Hủy tải"
            progressBar.visibility = View.VISIBLE
            statusText.visibility = View.VISIBLE
            statusText.text = "Đang kết nối trang nguồn..."

            Executors.newSingleThreadExecutor().execute {
                try {
                    val file = WebNovelCrawler.crawl(
                        this,
                        url,
                        limit,
                        { crawlCancelled },
                        { cur, total, chapTitle ->
                            runOnUiThread {
                                progressBar.max = total
                                progressBar.progress = cur
                                statusText.text = "[$cur/$total] $chapTitle"
                            }
                        }
                    )
                    runOnUiThread {
                        selectedUri = Uri.fromFile(file)
                        selectedName = file.name
                        binding.fileNameText.text = "${file.name} (Đã tải xong từ Web!)"
                        binding.translateButton.text = "Bắt đầu dịch trực tiếp (TXT)"
                        binding.statusText.text = "Đã tải xong ${file.name}. Bạn có thể bấm dịch ngay hoặc đẩy lên server!"
                        toast("Đã tải xong truyện về máy!")
                        dialog.dismiss()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        startBtn.isEnabled = true
                        urlInput.isEnabled = true
                        limitInput.isEnabled = true
                        cancelBtn.text = "Đóng"
                        statusText.text = "Lỗi: ${e.message}"
                        toast("Tải truyện thất bại: ${e.message}")
                    }
                }
            }
        }

        cancelBtn.setOnClickListener {
            crawlCancelled = true
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showGlossaryDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_glossary, null)
        val scopeRadioGroup = dialogView.findViewById<RadioGroup>(R.id.scopeRadioGroup)
        val searchInput = dialogView.findViewById<EditText>(R.id.searchGlossaryInput)
        val listView = dialogView.findViewById<ListView>(R.id.glossaryListView)
        val countText = dialogView.findViewById<TextView>(R.id.glossaryCountText)
        val addBtn = dialogView.findViewById<Button>(R.id.addTermButton)
        val closeBtn = dialogView.findViewById<Button>(R.id.closeGlossaryButton)

        val memoryStore = StoryMemoryStore(this)
        val currentBookKey = if (selectedName.isNotBlank() && selectedName != "Chưa chọn tệp.") {
            selectedName.removeSuffix(".txt").removeSuffix(".epub").take(48)
        } else {
            "current_book"
        }

        var activeScopeKey = currentBookKey
        var memory = memoryStore.load(activeScopeKey)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        fun refreshList(query: String = "") {
            val allNames = memory.getNamesMap()
            val filtered = if (query.isBlank()) {
                allNames.toList()
            } else {
                allNames.filter {
                    it.key.contains(query, ignoreCase = true) || it.value.contains(query, ignoreCase = true)
                }.toList()
            }
            countText.text = "Tổng số: ${filtered.size} từ"
            val displayList = filtered.map { "${it.first}  ➔  ${it.second}" }
            val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayList)
            listView.adapter = adapter

            listView.setOnItemClickListener { _, _, position, _ ->
                val (source, target) = filtered[position]
                showEditTermDialog(source, target) { newTarget, shouldDelete ->
                    if (shouldDelete) {
                        memory.removeName(source)
                    } else if (newTarget != null) {
                        memory.setName(source, newTarget)
                    }
                    memoryStore.save(activeScopeKey, memory)
                    refreshList(searchInput.text.toString())
                }
            }
        }

        scopeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            activeScopeKey = if (checkedId == R.id.scopeGlobalRadio) "global" else currentBookKey
            memory = memoryStore.load(activeScopeKey)
            refreshList(searchInput.text.toString())
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                refreshList(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        addBtn.setOnClickListener {
            showAddTermDialog { src, tgt ->
                if (src.isNotBlank() && tgt.isNotBlank()) {
                    memory.setName(src, tgt)
                    memoryStore.save(activeScopeKey, memory)
                    refreshList(searchInput.text.toString())
                    toast("Đã thêm: $src ➔ $tgt")
                }
            }
        }

        closeBtn.setOnClickListener { dialog.dismiss() }

        refreshList()
        dialog.show()
    }

    private fun showAddTermDialog(onAdd: (String, String) -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val sourceInput = EditText(this).apply {
            hint = "Từ gốc (Tiếng Trung/Anh, ví dụ: 萧炎)"
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input)
            setPadding(24, 20, 24, 20)
        }
        val targetInput = EditText(this).apply {
            hint = "Từ dịch (Tiếng Việt, ví dụ: Tiêu Viêm)"
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input)
            setPadding(24, 20, 24, 20)
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.topMargin = 20
            layoutParams = params
        }
        layout.addView(sourceInput)
        layout.addView(targetInput)

        AlertDialog.Builder(this)
            .setTitle("Thêm nhân vật / thuật ngữ mới")
            .setView(layout)
            .setPositiveButton("Thêm") { _, _ ->
                onAdd(sourceInput.text.toString().trim(), targetInput.text.toString().trim())
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showEditTermDialog(source: String, currentTarget: String, onComplete: (String?, Boolean) -> Unit) {
        val input = EditText(this).apply {
            setText(currentTarget)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input)
            setPadding(24, 20, 24, 20)
        }
        val container = LinearLayout(this).apply {
            setPadding(48, 24, 48, 24)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Sửa từ dịch cho: $source")
            .setView(container)
            .setPositiveButton("Lưu") { _, _ ->
                onComplete(input.text.toString().trim(), false)
            }
            .setNeutralButton("Xóa từ này") { _, _ ->
                onComplete(null, true)
            }
            .setNegativeButton("Hủy", null)
            .show()
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
        TranslationNotificationManager.showProgress(this, selectedName, done, total, status)
    }

    private fun setBusy(busy: Boolean) {
        binding.translateButton.isEnabled = !busy
        binding.selectFileButton.isEnabled = !busy
        binding.crawlWebButton.isEnabled = !busy
        binding.glossaryButton.isEnabled = !busy
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
