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
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
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
    private var serverMonitorExecutor: java.util.concurrent.ScheduledExecutorService? = null

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
            Toast.makeText(this, "Đã lưu bản dịch.", Toast.LENGTH_SHORT).show()
            AlertDialog.Builder(this)
                .setTitle("Lưu thành công! 🎉")
                .setMessage("Đã lưu \"${output.suggestedName}\" vào thiết bị.\n\nBạn có muốn mở đọc ngay không?")
                .setPositiveButton("Mở đọc ngay") { _, _ ->
                    val mime = if (output.suggestedName.endsWith(".txt", true)) "text/plain" else "application/epub+zip"
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { startActivity(Intent.createChooser(intent, "Mở bằng")) }
                }
                .setNegativeButton("Đóng", null)
                .show()
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
        binding.serverEpubText.text = "Đang tải \"${epub.displayTitle}\" từ server..."
        Executors.newSingleThreadExecutor().execute {
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    ServerEpubRepository(this, settings).download(epub.id, output)
                } ?: error("Không thể mở nơi lưu EPUB.")
                runOnUiThread {
                    binding.serverEpubText.text = "Đã tải: ${epub.displayTitle}"
                    AlertDialog.Builder(this)
                        .setTitle("Tải thành công! 🎉")
                        .setMessage("Đã lưu \"${epub.displayTitle}\" vào thiết bị.\n\nBạn có muốn mở đọc ngay không?")
                        .setPositiveButton("Mở đọc ngay") { _, _ ->
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/epub+zip")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { startActivity(Intent.createChooser(intent, "Mở bằng")) }
                        }
                        .setNegativeButton("Đóng", null)
                        .show()
                }
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
        serverMonitorExecutor?.shutdownNow()
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
        binding.checkUpdateButton.setOnClickListener {
            AppUpdateManager.checkForUpdate(this, showToastIfLatest = true)
        }
        binding.translateButton.setOnClickListener { startTranslation() }
        binding.polishLocalButton.setOnClickListener {
            val uri = selectedUri
            if (uri == null) {
                toast("Hãy chọn tệp EPUB trước khi chuốt văn phong.")
                return@setOnClickListener
            }
            val settings = settingsFromForm() ?: return@setOnClickListener
            repository.save(settings)
            startPolishLocalBook(uri, selectedName, settings)
        }
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
                    startServerJobMonitoring(job.id, uploadName, settings)
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

    private fun startServerJobMonitoring(jobId: String, bookName: String, settings: TranslationSettings) {
        serverMonitorExecutor?.shutdownNow()
        val executor = Executors.newSingleThreadScheduledExecutor()
        serverMonitorExecutor = executor

        runOnUiThread {
            binding.progressBar.visibility = View.VISIBLE
            binding.progressBar.progress = 0
            binding.serverEpubText.text = "Server đã nhận tệp. Đang theo dõi tiến trình dịch theo thời gian thực..."
        }

        executor.scheduleWithFixedDelay({
            try {
                val jobInfo = ServerEpubRepository(this, settings).getJob(jobId)
                if (jobInfo != null) {
                    runOnUiThread {
                        val pct = if (jobInfo.total > 0) (jobInfo.completed * 100) / jobInfo.total else 0
                        binding.progressBar.visibility = View.VISIBLE
                        binding.progressBar.progress = pct

                        if (jobInfo.status == "complete") {
                            binding.serverEpubText.text = "✓ Server đã dịch xong toàn bộ ${jobInfo.total} chương! Bấm 'Xem EPUB' để tải về."
                            TranslationNotificationManager.showCompleted(this, "[Server] $bookName", jobInfo.total)
                            executor.shutdown()
                        } else if (jobInfo.status == "failed") {
                            binding.serverEpubText.text = "✗ Server báo lỗi: ${jobInfo.message}"
                            TranslationNotificationManager.showError(this, "[Server] $bookName", jobInfo.message)
                            executor.shutdown()
                        } else {
                            binding.serverEpubText.text = "Server đang dịch: ${jobInfo.completed}/${jobInfo.total} chương/phần ($pct%) — Đang chạy nền...\nBấm 'Xem EPUB' để tải bản tạm khi cần đọc."
                            TranslationNotificationManager.showProgress(
                                this,
                                "[Server] $bookName",
                                jobInfo.completed,
                                jobInfo.total,
                                "Server đã dịch: ${jobInfo.completed}/${jobInfo.total} chương ($pct%)"
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }, 2, 5, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun showServerEpubs() {
        val settings = settingsFromForm() ?: return
        repository.save(settings)
        val repo = ServerEpubRepository(this, settings)

        // 1. Tải tức thì từ bộ nhớ đệm máy (Cache) nếu có
        val cachedBooks = repo.getCachedList()
        if (cachedBooks.isNotEmpty()) {
            binding.serverEpubText.text = "Có ${cachedBooks.size} truyện (Mở từ bộ nhớ đệm ⚡)"
            showServerBooksDialog(cachedBooks, repo, settings, isCachedInitial = true)
        } else {
            binding.serverEpubButton.isEnabled = false
            binding.serverEpubText.text = "Đang lấy danh sách truyện từ Google Drive & Server..."
            Executors.newSingleThreadExecutor().execute {
                try {
                    val books = repo.list()
                    runOnUiThread {
                        binding.serverEpubButton.isEnabled = true
                        if (books.isEmpty()) {
                            binding.serverEpubText.text = "Server chưa có truyện nào."
                            return@runOnUiThread
                        }
                        binding.serverEpubText.text = "Có ${books.size} truyện trên Google Drive & Server."
                        showServerBooksDialog(books, repo, settings, isCachedInitial = false)
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        binding.serverEpubButton.isEnabled = true
                        binding.serverEpubText.text = "Không xem được kho truyện: ${error.message}"
                    }
                }
            }
        }
    }

    private fun showServerBooksDialog(
        initialBooks: List<RemoteEpub>,
        repo: ServerEpubRepository,
        settings: TranslationSettings,
        isCachedInitial: Boolean = false
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_server_epubs, null)
        val listView = dialogView.findViewById<ListView>(R.id.serverBooksListView)
        val closeBtn = dialogView.findViewById<Button>(R.id.dialogCloseButton)
        val searchEdit = dialogView.findViewById<EditText>(R.id.searchEditText)
        val statsText = dialogView.findViewById<TextView>(R.id.dialogStatsText)
        val subtitleText = dialogView.findViewById<TextView>(R.id.dialogSubtitleText)
        val refreshBtn = dialogView.findViewById<Button>(R.id.dialogRefreshButton)
        val cacheBadge = dialogView.findViewById<TextView>(R.id.cacheStatusBadge)

        // Filter chips - Source (Nguồn truyện)
        val chipSourceAll = dialogView.findViewById<TextView>(R.id.chipSourceAll)
        val chipSourceAuto = dialogView.findViewById<TextView>(R.id.chipSourceAuto)
        val chipSourceUpload = dialogView.findViewById<TextView>(R.id.chipSourceUpload)

        // Filter chips - Status (Trạng thái)
        val chipStatusAll = dialogView.findViewById<TextView>(R.id.chipStatusAll)
        val chipStatusDone = dialogView.findViewById<TextView>(R.id.chipStatusDone)
        val chipStatusTranslating = dialogView.findViewById<TextView>(R.id.chipStatusTranslating)

        // Genre Spinner (Thể loại như TimoTXT)
        val genreSpinner = dialogView.findViewById<Spinner>(R.id.genreFilterSpinner)

        val genreList = listOf(
            "Tất cả thể loại",
            "Tiên Hiệp",
            "Huyền Huyễn",
            "Đô Thị",
            "Khoa Huyễn",
            "Võng Du / Thể Thao",
            "Lịch Sử / Quân Sự",
            "Đồng Nhân",
            "Khác"
        )
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, genreList)
        genreSpinner.adapter = spinnerAdapter

        val allBooks = initialBooks.toMutableList()
        val filteredBooks = ArrayList<RemoteEpub>(initialBooks)

        var sourceFilter = 0 // 0: All, 1: Auto-translated, 2: Uploaded
        var statusFilter = 0 // 0: All, 1: Complete, 2: Running
        var selectedGenre = "Tất cả thể loại"
        var searchQuery = ""

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        closeBtn.setOnClickListener { dialog.dismiss() }

        val imageExecutor = Executors.newFixedThreadPool(4)

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = filteredBooks.size
            override fun getItem(position: Int): RemoteEpub = filteredBooks[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_server_epub, parent, false)
                val book = getItem(position)

                val coverImg = view.findViewById<ImageView>(R.id.bookCoverImage)
                val titleTxt = view.findViewById<TextView>(R.id.bookTitleText)
                val authorTxt = view.findViewById<TextView>(R.id.bookAuthorText)
                val statusBadge = view.findViewById<TextView>(R.id.bookStatusBadge)
                val originBadge = view.findViewById<TextView>(R.id.bookOriginBadge)
                val genreBadge = view.findViewById<TextView>(R.id.bookGenreBadge)
                val progressBar = view.findViewById<ProgressBar>(R.id.bookProgressBar)
                val progressTxt = view.findViewById<TextView>(R.id.bookProgressText)
                val actionHint = view.findViewById<TextView>(R.id.bookActionHint)

                titleTxt.text = book.displayTitle
                val authorPart = if (book.author.isNotBlank()) "Tác giả: ${book.author}" else ""
                val modelPart = if (book.model.isNotBlank()) " • ${book.model}" else ""
                authorTxt.text = (authorPart + modelPart).ifBlank { "Tác giả: Đang cập nhật" }

                // Origin Badge
                if (book.isAutoTranslated) {
                    originBadge.text = "🤖 Tự động (Timo)"
                    originBadge.setBackgroundResource(R.drawable.bg_badge_auto)
                    originBadge.setTextColor(getColor(R.color.badge_auto_text))
                } else {
                    originBadge.text = "📤 Tải lên"
                    originBadge.setBackgroundResource(R.drawable.bg_badge_upload)
                    originBadge.setTextColor(getColor(R.color.badge_upload_text))
                }

                // Genre Badge
                genreBadge.text = book.genre

                progressBar.progress = book.progressPercent

                if (book.isComplete) {
                    statusBadge.text = "✓ Hoàn thành"
                    statusBadge.setBackgroundResource(R.drawable.bg_badge_complete)
                    statusBadge.setTextColor(getColor(R.color.badge_complete_text))
                    progressTxt.text = "${book.completed} / ${book.total} chương (100%)"
                    actionHint.text = "Chạm xem chi tiết"
                } else {
                    statusBadge.text = "⏳ Đang dịch"
                    statusBadge.setBackgroundResource(R.drawable.bg_badge_running)
                    statusBadge.setTextColor(getColor(R.color.badge_running_text))
                    progressTxt.text = "${book.completed} / ${book.total} chương (${book.progressPercent}%)"
                    actionHint.text = "Chạm xem chi tiết"
                }

                val btnQuickExport = view.findViewById<Button>(R.id.btnQuickExport)
                val btnQuickPolish = view.findViewById<Button>(R.id.btnQuickPolish)

                btnQuickExport.text = if (book.isComplete) "📥 Tải gốc" else "⚡ Xuất (${book.completed}c)"
                btnQuickExport.setOnClickListener {
                    onExportRawClicked(book, repo, settings, dialog)
                }

                btnQuickPolish.setOnClickListener {
                    onPolishClicked(book, repo, settings, dialog)
                }

                coverImg.setImageResource(R.drawable.ic_book_placeholder)
                coverImg.tag = book.id
                imageExecutor.execute {
                    val bmp = repo.loadCoverBitmap(book.id)
                    if (bmp != null) {
                        runOnUiThread {
                            if (coverImg.tag == book.id) {
                                coverImg.setImageBitmap(bmp)
                            }
                        }
                    }
                }

                return view
            }
        }

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in 0 until filteredBooks.size) {
                val book = filteredBooks[position]
                onServerBookSelected(book, repo, settings, dialog)
            }
        }

        fun applyFilter() {
            val q = searchQuery.trim().lowercase()
            filteredBooks.clear()
            filteredBooks.addAll(allBooks.filter { book ->
                val matchSource = when (sourceFilter) {
                    1 -> book.isAutoTranslated
                    2 -> book.isUploaded
                    else -> true
                }
                val matchStatus = when (statusFilter) {
                    1 -> book.isComplete
                    2 -> !book.isComplete
                    else -> true
                }
                val matchGenre = when (selectedGenre) {
                    "Tất cả thể loại" -> true
                    else -> book.genre.equals(selectedGenre, ignoreCase = true)
                }
                val matchQuery = if (q.isBlank()) true else {
                    book.displayTitle.lowercase().contains(q) ||
                    book.author.lowercase().contains(q) ||
                    book.genre.lowercase().contains(q) ||
                    book.name.lowercase().contains(q)
                }
                matchSource && matchStatus && matchGenre && matchQuery
            })
            adapter.notifyDataSetChanged()
            statsText.text = "Hiển thị ${filteredBooks.size}/${allBooks.size} truyện"
        }

        fun updateSourceChips() {
            chipSourceAll.setBackgroundResource(if (sourceFilter == 0) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            chipSourceAll.setTextColor(getColor(if (sourceFilter == 0) R.color.paper else R.color.muted))

            chipSourceAuto.setBackgroundResource(if (sourceFilter == 1) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            chipSourceAuto.setTextColor(getColor(if (sourceFilter == 1) R.color.paper else R.color.muted))

            chipSourceUpload.setBackgroundResource(if (sourceFilter == 2) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            chipSourceUpload.setTextColor(getColor(if (sourceFilter == 2) R.color.paper else R.color.muted))
        }

        fun updateStatusChips() {
            chipStatusAll.setBackgroundResource(if (statusFilter == 0) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            chipStatusAll.setTextColor(getColor(if (statusFilter == 0) R.color.paper else R.color.muted))

            chipStatusDone.setBackgroundResource(if (statusFilter == 1) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            chipStatusDone.setTextColor(getColor(if (statusFilter == 1) R.color.paper else R.color.muted))

            chipStatusTranslating.setBackgroundResource(if (statusFilter == 2) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            chipStatusTranslating.setTextColor(getColor(if (statusFilter == 2) R.color.paper else R.color.muted))
        }

        chipSourceAll.setOnClickListener { sourceFilter = 0; updateSourceChips(); applyFilter() }
        chipSourceAuto.setOnClickListener { sourceFilter = 1; updateSourceChips(); applyFilter() }
        chipSourceUpload.setOnClickListener { sourceFilter = 2; updateSourceChips(); applyFilter() }

        chipStatusAll.setOnClickListener { statusFilter = 0; updateStatusChips(); applyFilter() }
        chipStatusDone.setOnClickListener { statusFilter = 1; updateStatusChips(); applyFilter() }
        chipStatusTranslating.setOnClickListener { statusFilter = 2; updateStatusChips(); applyFilter() }

        genreSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedGenre = genreList[position]
                applyFilter()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString() ?: ""
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        fun fetchFreshServerBooks() {
            statsText.text = "Đang kiểm tra truyện mới từ server..."
            refreshBtn.isEnabled = false
            Executors.newSingleThreadExecutor().execute {
                try {
                    val fresh = repo.list(saveCache = true)
                    runOnUiThread {
                        refreshBtn.isEnabled = true
                        if (fresh.isNotEmpty()) {
                            val oldCount = allBooks.size
                            allBooks.clear()
                            allBooks.addAll(fresh)
                            applyFilter()
                            cacheBadge.text = "✓ Đã đồng bộ"
                            cacheBadge.setBackgroundResource(R.drawable.bg_badge_complete)
                            cacheBadge.setTextColor(getColor(R.color.badge_complete_text))
                            val diff = fresh.size - oldCount
                            if (diff > 0) {
                                subtitleText.text = "Đã cập nhật thêm $diff truyện mới từ Server!"
                            } else {
                                subtitleText.text = "Kho truyện đã ở trạng thái mới nhất (${fresh.size} truyện)."
                            }
                        } else {
                            statsText.text = "Không có truyện mới."
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        refreshBtn.isEnabled = true
                        statsText.text = "Lỗi kết nối: ${e.message}"
                    }
                }
            }
        }

        refreshBtn.setOnClickListener {
            fetchFreshServerBooks()
        }

        applyFilter()
        dialog.show()

        // Nếu mở từ bộ nhớ đệm, tự động kiểm tra cập nhật mới ngầm trong nền
        if (isCachedInitial) {
            fetchFreshServerBooks()
        }
    }

    private fun onExportRawClicked(
        book: RemoteEpub,
        repo: ServerEpubRepository,
        settings: TranslationSettings,
        parentDialog: AlertDialog? = null
    ) {
        parentDialog?.dismiss()
        if (book.isComplete) {
            pendingServerEpub = book
            val cleanFileName = book.displayTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".vi.epub"
            serverEpubCreator.launch(cleanFileName)
        } else {
            Toast.makeText(this, "Đang đóng gói EPUB từ server...", Toast.LENGTH_SHORT).show()
            Executors.newSingleThreadExecutor().execute {
                val updated = repo.export(book.id) ?: book
                runOnUiThread {
                    pendingServerEpub = updated
                    startServerJobMonitoring(updated.id, updated.name, settings)
                    val cleanFileName = updated.displayTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".vi.epub"
                    serverEpubCreator.launch(cleanFileName)
                }
            }
        }
    }

    private fun onPolishClicked(
        book: RemoteEpub,
        repo: ServerEpubRepository,
        settings: TranslationSettings,
        parentDialog: AlertDialog? = null
    ) {
        val setupView = layoutInflater.inflate(R.layout.dialog_polish_setup, null)
        val titleTxt = setupView.findViewById<TextView>(R.id.polishBookTitle)
        val availTxt = setupView.findViewById<TextView>(R.id.polishAvailableChapters)
        val startEdit = setupView.findViewById<EditText>(R.id.polishChapterStart)
        val endEdit = setupView.findViewById<EditText>(R.id.polishChapterEnd)
        val concurrencyEdit = setupView.findViewById<EditText>(R.id.polishConcurrency)
        val btnCancel = setupView.findViewById<Button>(R.id.btnCancelPolish)
        val btnStart = setupView.findViewById<Button>(R.id.btnStartPolish)

        titleTxt.text = "Truyện: ${book.displayTitle}"
        availTxt.text = "Có sẵn: ${book.completed}/${book.total} chương (${book.progressPercent}%)"
        startEdit.setText("1")
        val defaultEnd = if (book.completed > 0) book.completed else book.total
        if (defaultEnd > 0) {
            endEdit.setText(defaultEnd.toString())
        }
        concurrencyEdit.setText("8")

        val setupDialog = AlertDialog.Builder(this)
            .setView(setupView)
            .create()

        btnCancel.setOnClickListener { setupDialog.dismiss() }

        btnStart.setOnClickListener {
            val startChap = startEdit.text.toString().toIntOrNull() ?: 1
            val endChap = endEdit.text.toString().toIntOrNull() ?: defaultEnd
            val concurrency = (concurrencyEdit.text.toString().toIntOrNull() ?: 8).coerceIn(1, 15)

            setupDialog.dismiss()
            parentDialog?.dismiss()

            startPolishServerBook(book, repo, settings, startChap, endChap, concurrency)
        }

        setupDialog.show()
    }

    private fun startPolishServerBook(
        book: RemoteEpub,
        repo: ServerEpubRepository,
        baseSettings: TranslationSettings,
        startChap: Int,
        endChap: Int,
        concurrency: Int
    ) {
        if (activeWork != null && !activeWork!!.isDone) {
            toast("Đang có tác vụ dịch hoặc chuốt văn phong đang chạy!")
            return
        }

        setBusy(true)
        binding.statusText.text = "Đang chuẩn bị tệp EPUB từ server để chuốt văn phong Flash 3.8..."
        cancelled = false
        pauseRequested = false

        activeWork = Executors.newSingleThreadExecutor().submit {
            try {
                val targetBook = if (!book.isComplete) {
                    runOnUiThread { binding.statusText.text = "Đang đồng bộ các chương mới nhất từ server..." }
                    repo.export(book.id) ?: book
                } else book

                val tempFile = File(cacheDir, "server_for_polish_${book.id}.epub")
                if (tempFile.exists()) tempFile.delete()

                runOnUiThread { binding.statusText.text = "Đang tải EPUB (${targetBook.completed} chương) từ server vào máy..." }
                java.io.FileOutputStream(tempFile).use { out ->
                    repo.download(targetBook.id, out)
                }

                if (!tempFile.exists() || tempFile.length() == 0L) {
                    throw TranslationException("Không tải được tệp EPUB từ server.")
                }

                val polishSettings = baseSettings.copy(
                    model = "ag/gemini-3.8-flash",
                    concurrency = concurrency,
                    chapterStart = startChap,
                    chapterEnd = endChap,
                    genre = book.genre
                )

                runOnUiThread {
                    binding.statusText.text = "Bắt đầu chuốt văn phong bằng Gemini Flash 3.8 ($concurrency luồng)..."
                    TranslationNotificationManager.showProgress(
                        this@MainActivity,
                        "[Chuốt FL3.8] " + book.displayTitle,
                        0,
                        (endChap - startChap + 1).coerceAtLeast(1)
                    )
                }

                val cleanTitle = book.displayTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val output = BookTranslator(this).translate(
                    Uri.fromFile(tempFile),
                    "$cleanTitle.epub",
                    polishSettings,
                    { cancelled || Thread.currentThread().isInterrupted },
                    { pauseRequested },
                    { cur, total, msg ->
                        publishProgress(cur, total, "[Chuốt FL3.8] $msg")
                    },
                    isPolishMode = true
                )

                runOnUiThread {
                    setBusy(false)
                    pendingOutput = output
                    if (output.isComplete) {
                        TranslationNotificationManager.showCompleted(
                            this@MainActivity,
                            "[Chuốt FL3.8] " + book.displayTitle,
                            output.totalChapters
                        )
                    }
                    binding.statusText.text = if (output.paused) {
                        "Đã tạm dừng chuốt văn phong. Đã hoàn thành ${output.completedChapters}/${output.totalChapters} chương."
                    } else if (output.isComplete) {
                        "Chuốt văn phong FL3.8 hoàn tất! Chọn nơi lưu ${output.suggestedName}."
                    } else {
                        "Đã lưu ${output.completedChapters}/${output.totalChapters} chương đã chuốt. Bạn có thể lưu bản tạm."
                    }
                    outputCreator.launch(output.suggestedName)
                }
            } catch (e: CancellationException) {
                runOnUiThread {
                    setBusy(false)
                    TranslationNotificationManager.cancel(this@MainActivity)
                    binding.statusText.text = "Đã hủy tác vụ chuốt văn phong."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    TranslationNotificationManager.showError(
                        this@MainActivity,
                        "[Chuốt FL3.8] " + book.displayTitle,
                        e.message ?: "Lỗi"
                    )
                    binding.statusText.text = "Lỗi chuốt văn phong: ${e.message}"
                    toast("Lỗi chuốt văn phong: ${e.message}")
                }
            }
        }
    }

    private fun startPolishLocalBook(
        uri: Uri,
        name: String,
        baseSettings: TranslationSettings
    ) {
        if (activeWork != null && !activeWork!!.isDone) {
            toast("Đang có tác vụ dịch hoặc chuốt văn phong đang chạy!")
            return
        }

        val setupView = layoutInflater.inflate(R.layout.dialog_polish_setup, null)
        val titleTxt = setupView.findViewById<TextView>(R.id.polishBookTitle)
        val availTxt = setupView.findViewById<TextView>(R.id.polishAvailableChapters)
        val startEdit = setupView.findViewById<EditText>(R.id.polishChapterStart)
        val endEdit = setupView.findViewById<EditText>(R.id.polishChapterEnd)
        val concurrencyEdit = setupView.findViewById<EditText>(R.id.polishConcurrency)
        val btnCancel = setupView.findViewById<Button>(R.id.btnCancelPolish)
        val btnStart = setupView.findViewById<Button>(R.id.btnStartPolish)

        val cleanTitle = name.removeSuffix(".epub").removeSuffix(".txt")
        titleTxt.text = "Tệp: $cleanTitle"
        availTxt.text = "Tệp trên thiết bị: $name"
        startEdit.setText("1")
        if (baseSettings.chapterEnd > 0) {
            endEdit.setText(baseSettings.chapterEnd.toString())
        }
        concurrencyEdit.setText(baseSettings.concurrency.coerceIn(1, 15).toString())

        val setupDialog = AlertDialog.Builder(this)
            .setView(setupView)
            .create()

        btnCancel.setOnClickListener { setupDialog.dismiss() }

        btnStart.setOnClickListener {
            val startChap = startEdit.text.toString().toIntOrNull() ?: 1
            val endChap = endEdit.text.toString().toIntOrNull() ?: 0
            val concurrency = (concurrencyEdit.text.toString().toIntOrNull() ?: 8).coerceIn(1, 15)

            setupDialog.dismiss()

            setBusy(true)
            binding.statusText.text = "Bắt đầu chuốt văn phong bằng Gemini Flash 3.8 ($concurrency luồng)..."
            cancelled = false
            pauseRequested = false

            val polishSettings = baseSettings.copy(
                model = "ag/gemini-3.8-flash",
                concurrency = concurrency,
                chapterStart = startChap,
                chapterEnd = endChap
            )

            activeWork = Executors.newSingleThreadExecutor().submit {
                try {
                    val output = BookTranslator(this).translate(
                        uri,
                        name,
                        polishSettings,
                        { cancelled || Thread.currentThread().isInterrupted },
                        { pauseRequested },
                        { cur, total, msg ->
                            publishProgress(cur, total, "[Chuốt FL3.8] $msg")
                        },
                        isPolishMode = true
                    )

                    runOnUiThread {
                        setBusy(false)
                        pendingOutput = output
                        if (output.isComplete) {
                            TranslationNotificationManager.showCompleted(
                                this@MainActivity,
                                "[Chuốt FL3.8] $cleanTitle",
                                output.totalChapters
                            )
                        }
                        binding.statusText.text = if (output.paused) {
                            "Đã tạm dừng chuốt văn phong. Đã hoàn thành ${output.completedChapters}/${output.totalChapters} chương."
                        } else if (output.isComplete) {
                            "Chuốt văn phong FL3.8 hoàn tất! Chọn nơi lưu ${output.suggestedName}."
                        } else {
                            "Đã lưu ${output.completedChapters}/${output.totalChapters} chương đã chuốt. Bạn có thể lưu bản tạm."
                        }
                        outputCreator.launch(output.suggestedName)
                    }
                } catch (e: CancellationException) {
                    runOnUiThread {
                        setBusy(false)
                        TranslationNotificationManager.cancel(this@MainActivity)
                        binding.statusText.text = "Đã hủy tác vụ chuốt văn phong."
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        setBusy(false)
                        TranslationNotificationManager.showError(
                            this@MainActivity,
                            "[Chuốt FL3.8] $cleanTitle",
                            e.message ?: "Lỗi"
                        )
                        binding.statusText.text = "Lỗi chuốt văn phong: ${e.message}"
                        toast("Lỗi chuốt văn phong: ${e.message}")
                    }
                }
            }
        }

        setupDialog.show()
    }

    private fun onServerBookSelected(
        book: RemoteEpub,
        repo: ServerEpubRepository,
        settings: TranslationSettings,
        parentDialog: AlertDialog? = null
    ) {
        val actionView = layoutInflater.inflate(R.layout.dialog_server_book_action, null)
        val coverImg = actionView.findViewById<ImageView>(R.id.dialogBookCover)
        val titleTxt = actionView.findViewById<TextView>(R.id.dialogBookTitle)
        val authorTxt = actionView.findViewById<TextView>(R.id.dialogBookAuthor)
        val originBadge = actionView.findViewById<TextView>(R.id.dialogBookOrigin)
        val genreBadge = actionView.findViewById<TextView>(R.id.dialogBookGenre)
        val statusBadge = actionView.findViewById<TextView>(R.id.dialogBookStatus)
        val modelTxt = actionView.findViewById<TextView>(R.id.dialogBookModel)
        val progressBar = actionView.findViewById<ProgressBar>(R.id.dialogBookProgress)
        val progressTxt = actionView.findViewById<TextView>(R.id.dialogBookProgressText)

        val btnPolish = actionView.findViewById<View>(R.id.btnActionPolish)
        val btnExportRaw = actionView.findViewById<View>(R.id.btnActionExportRaw)
        val txtExportRawTitle = actionView.findViewById<TextView>(R.id.txtExportRawTitle)
        val btnTrack = actionView.findViewById<Button>(R.id.btnActionTrack)
        val btnClose = actionView.findViewById<Button>(R.id.btnActionClose)

        titleTxt.text = book.displayTitle
        authorTxt.text = if (book.author.isNotBlank()) "Tác giả: ${book.author}" else "Tác giả: Đang cập nhật"
        modelTxt.text = if (book.model.isNotBlank()) "Mô hình dịch gốc: ${book.model}" else "Mô hình: Đang cập nhật"

        if (book.isAutoTranslated) {
            originBadge.text = "🤖 Tự động"
            originBadge.setBackgroundResource(R.drawable.bg_badge_auto)
            originBadge.setTextColor(getColor(R.color.badge_auto_text))
        } else {
            originBadge.text = "📤 Tải lên"
            originBadge.setBackgroundResource(R.drawable.bg_badge_upload)
            originBadge.setTextColor(getColor(R.color.badge_upload_text))
        }

        genreBadge.text = book.genre
        progressBar.progress = book.progressPercent

        if (book.isComplete) {
            statusBadge.text = "✓ Hoàn thành"
            statusBadge.setBackgroundResource(R.drawable.bg_badge_complete)
            statusBadge.setTextColor(getColor(R.color.badge_complete_text))
            progressTxt.text = "${book.completed} / ${book.total} chương (100%)"
            txtExportRawTitle.text = "📥 Tải bản EPUB gốc (${book.completed} chương)"
            btnTrack.visibility = View.GONE
        } else {
            statusBadge.text = "⏳ Đang dịch"
            statusBadge.setBackgroundResource(R.drawable.bg_badge_running)
            statusBadge.setTextColor(getColor(R.color.badge_running_text))
            progressTxt.text = "${book.completed} / ${book.total} chương (${book.progressPercent}%)"
            txtExportRawTitle.text = "⚡ Xuất EPUB gốc (${book.completed} chương)"
            btnTrack.visibility = View.VISIBLE
        }

        coverImg.setImageResource(R.drawable.ic_book_placeholder)
        Executors.newSingleThreadExecutor().execute {
            val bmp = repo.loadCoverBitmap(book.id)
            if (bmp != null) {
                runOnUiThread { coverImg.setImageBitmap(bmp) }
            }
        }

        val actionDialog = AlertDialog.Builder(this)
            .setView(actionView)
            .create()

        btnClose.setOnClickListener { actionDialog.dismiss() }

        btnExportRaw.setOnClickListener {
            actionDialog.dismiss()
            onExportRawClicked(book, repo, settings, parentDialog)
        }

        btnPolish.setOnClickListener {
            actionDialog.dismiss()
            onPolishClicked(book, repo, settings, parentDialog)
        }

        btnTrack.setOnClickListener {
            actionDialog.dismiss()
            parentDialog?.dismiss()
            startServerJobMonitoring(book.id, book.name, settings)
            toast("Đang theo dõi: ${book.displayTitle}")
        }

        actionDialog.show()
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
        if (threads == null || threads !in 1..10) {
            binding.concurrencyInput.error = "Chọn từ 1 đến 10 luồng (tối đa 10 luồng)"
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
            concurrency = threads.coerceIn(1, 10),
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
