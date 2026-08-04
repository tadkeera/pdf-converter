package com.tadkeera.pdfconverter

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tadkeera.pdfconverter.convert.ConvertOptions
import com.tadkeera.pdfconverter.convert.Converter
import com.tadkeera.pdfconverter.convert.FileResult
import com.tadkeera.pdfconverter.util.TextUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private val pdfUris = mutableListOf<Uri>()
    private val pdfNames = mutableListOf<String>()
    private lateinit var adapter: PdfAdapter

    private lateinit var btnAdd: Button
    private lateinit var btnClear: Button
    private lateinit var btnConvert: Button
    private lateinit var btnShare: Button
    private lateinit var etSkipPages: EditText
    private lateinit var etSkipKeywords: EditText
    private lateinit var etMaxPages: EditText
    private lateinit var cbAutoName: CheckBox
    private lateinit var etManualName: EditText
    private lateinit var progress: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var logText: TextView

    private val results = mutableListOf<FileResult>()
    private var busy = false

    private val pickPdf =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            addPdf(uri)
        }

    private val pickMultiple =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri>? ->
            uris?.forEach { addPdf(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnAdd = findViewById(R.id.btnAdd)
        btnClear = findViewById(R.id.btnClear)
        btnConvert = findViewById(R.id.btnConvert)
        btnShare = findViewById(R.id.btnShare)
        etSkipPages = findViewById(R.id.etSkipPages)
        etSkipKeywords = findViewById(R.id.etSkipKeywords)
        etMaxPages = findViewById(R.id.etMaxPages)
        cbAutoName = findViewById(R.id.cbAutoName)
        etManualName = findViewById(R.id.etManualName)
        progress = findViewById(R.id.progress)
        tvStatus = findViewById(R.id.tvStatus)
        logText = findViewById(R.id.logText)

        val rv = findViewById<RecyclerView>(R.id.rvFiles)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = PdfAdapter { pos ->
            pdfUris.removeAt(pos)
            pdfNames.removeAt(pos)
            adapter.notifyItemRemoved(pos)
        }
        rv.adapter = adapter

        btnAdd.setOnClickListener { pickMultiple.launch(arrayOf("application/pdf")) }
        btnClear.setOnClickListener { pdfUris.clear(); pdfNames.clear(); adapter.notifyDataSetChanged() }
        btnConvert.setOnClickListener { startConversion() }
        btnShare.setOnClickListener { shareResults() }

        // single-file quick pick (long press)
        btnAdd.setOnLongClickListener {
            pickPdf.launch(arrayOf("application/pdf"))
            true
        }
    }

    private fun addPdf(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        val name = queryDisplayName(uri)
        if (name == null) {
            toast(getString(R.string.cannot_read_file))
            return
        }
        if (pdfUris.none { it == uri }) {
            pdfUris.add(uri)
            pdfNames.add(name)
            adapter.notifyItemInserted(pdfUris.size - 1)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
        return name
    }

    private fun startConversion() {
        if (busy) return
        if (pdfUris.isEmpty()) {
            toast(getString(R.string.add_files_first))
            return
        }
        val maxPages = etMaxPages.text.toString().toIntOrNull() ?: 1000
        val skipNumbers = TextUtils.parseSkipNumbers(etSkipPages.text.toString())
        val skipKeywords = TextUtils.parseKeywords(etSkipKeywords.text.toString())
        val manual = etManualName.text.toString().trim()
        val options = ConvertOptions(
            maxPages = maxPages,
            skipNumbers = skipNumbers,
            skipKeywords = skipKeywords,
            manualName = manual,
            autoName = cbAutoName.isChecked
        )

        busy = true
        btnConvert.isEnabled = false
        results.clear()
        btnShare.isVisible = false
        log("${getString(R.string.starting)} ${pdfUris.size} ${getString(R.string.files)}")
        log("${getString(R.string.max_pages_label)}: $maxPages | ${getString(R.string.skip_pages_label)}: ${skipNumbers.ifEmpty { "—" }} | ${getString(R.string.skip_keywords_label)}: ${skipKeywords.ifEmpty { "—" }}")

        val outDir = File(getExternalFilesDir(null), "PDFConverter").apply { mkdirs() }
        val total = pdfUris.size
        val inputs = pdfUris.toList()
        val names = pdfNames.toList()

        CoroutineScope(Dispatchers.IO).launch {
            var done = 0
            for ((i, uri) in inputs.withIndex()) {
                val name = names[i]
                val res = runCatching {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        Converter.convert(stream, name, options, outDir) { pageNo ->
                            runOnUiThread {
                                tvStatus.text = "${getString(R.string.processing)} $name — ${getString(R.string.page)} $pageNo"
                            }
                        }
                    } ?: FileResult(name, "", 0, 0, null, getString(R.string.cannot_read_file))
                }.getOrElse { e ->
                    FileResult(name, "", 0, 0, null, e.message ?: "error")
                }
                results.add(res)
                done++
                val pct = (done * 100) / total
                runOnUiThread {
                    progress.progress = pct
                    tvStatus.text = "$done / $total"
                    if (res.error != null) {
                        log("✖ $name — ${res.error}")
                    } else {
                        val fname = res.outputName
                        val merchant = res.merchant?.let { " | ${getString(R.string.merchant)}: $it" } ?: ""
                        log("✔ $name → $fname (${res.pagesConverted} ${getString(R.string.pages)}, ${res.pagesSkipped} ${getString(R.string.skipped)})$merchant")
                    }
                }
            }
            runOnUiThread {
                busy = false
                btnConvert.isEnabled = true
                tvStatus.text = getString(R.string.done)
                log("✔ ${getString(R.string.finished)} $total ${getString(R.string.files)}")
                if (results.any { it.error == null }) {
                    btnShare.isVisible = true
                    btnShare.text = getString(R.string.share_files, results.count { it.error == null })
                }
            }
        }
    }

    private fun shareResults() {
        val outDir = File(getExternalFilesDir(null), "PDFConverter")
        val uris = results.filter { it.error == null }
            .map { File(outDir, it.outputName) }
            .filter { it.exists() }
            .map { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) }
        if (uris.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }

    private fun log(msg: String) {
        logText.append("$msg\n")
        // keep the tail visible
        logText.post {
            val scroll = logText.parent as? View
            scroll?.post { scroll.scrollTo(0, scroll.bottom) }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------- adapter
    inner class PdfAdapter(
        private val onRemove: (Int) -> Unit
    ) : RecyclerView.Adapter<PdfAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tvFileName)
            val remove: Button = v.findViewById(R.id.btnRemove)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_pdf, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = pdfNames.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.name.text = pdfNames[position]
            holder.remove.setOnClickListener { onRemove(position) }
        }
    }
}
