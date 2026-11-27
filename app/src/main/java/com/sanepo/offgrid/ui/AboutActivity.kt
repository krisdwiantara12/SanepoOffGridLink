package com.sanepo.offgrid.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.sanepo.offgrid.BuildConfig
import com.sanepo.offgrid.R
import com.sanepo.offgrid.engine.AppUpdateManager
import java.util.Date
import java.util.concurrent.Executors

class AboutActivity : AppCompatActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private var latestRelease: AppUpdateManager.ReleaseInfo? = null

    private lateinit var tvUpdateStatus: TextView
    private lateinit var progressUpdateCheck: ProgressBar
    private lateinit var btnCheckUpdate: Button
    private lateinit var btnInstallUpdate: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_about)

        val root = findViewById<View>(R.id.aboutRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<View>(R.id.btnAboutBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvAboutVersion).text =
            "Versi ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"

        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        progressUpdateCheck = findViewById(R.id.progressUpdateCheck)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        btnInstallUpdate = findViewById(R.id.btnInstallUpdate)

        val cbAuto = findViewById<CheckBox>(R.id.cbAutoCheckUpdate)
        val cbPre = findViewById<CheckBox>(R.id.cbIncludePreRelease)
        cbAuto.isChecked = AppUpdateManager.isAutoCheckOnLaunch(this)
        cbPre.isChecked = AppUpdateManager.isIncludePreRelease(this)

        cbAuto.setOnCheckedChangeListener { _, checked ->
            AppUpdateManager.setAutoCheckOnLaunch(this, checked)
        }
        cbPre.setOnCheckedChangeListener { _, checked ->
            AppUpdateManager.setIncludePreRelease(this, checked)
        }

        val lastMs = AppUpdateManager.getLastCheckTimestampMs(this)
        if (lastMs > 0L) {
            val formatted = DateFormat.format("dd MMM yyyy HH:mm", Date(lastMs))
            val tag = AppUpdateManager.getLastRemoteTag(this) ?: "-"
            tvUpdateStatus.text = "Pemeriksaan terakhir: $formatted (Rilis GitHub: $tag)"
        }

        btnCheckUpdate.setOnClickListener { runUpdateCheck() }
        btnInstallUpdate.setOnClickListener { triggerDownloadAndInstall() }

        findViewById<Button>(R.id.btnOpenGithub).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdateManager.REPOSITORY_URL)))
            } catch (_: Throwable) {
                Toast.makeText(this, "Browser tidak tersedia.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun runUpdateCheck() {
        btnCheckUpdate.isEnabled = false
        btnInstallUpdate.visibility = View.GONE
        progressUpdateCheck.visibility = View.VISIBLE
        progressUpdateCheck.isIndeterminate = true
        tvUpdateStatus.text = "Memeriksa rilis terbaru di GitHub..."

        val includePre = AppUpdateManager.isIncludePreRelease(this)
        worker.execute {
            val outcome = AppUpdateManager.checkForUpdate(this, includePre)
            runOnUiThread {
                btnCheckUpdate.isEnabled = true
                progressUpdateCheck.visibility = View.GONE
                when (outcome) {
                    is AppUpdateManager.CheckOutcome.Success -> {
                        val rel = outcome.release
                        latestRelease = rel
                        if (rel.updateAvailable) {
                            tvUpdateStatus.text = "Tersedia ${rel.tagName} (${rel.releaseName}).\n${rel.body.take(260)}"
                            if (!rel.apkDownloadUrl.isNullOrBlank()) {
                                btnInstallUpdate.visibility = View.VISIBLE
                            }
                        } else {
                            tvUpdateStatus.text = "Sudah menggunakan versi terbaru (${BuildConfig.VERSION_NAME})."
                        }
                    }
                    is AppUpdateManager.CheckOutcome.NoReleasePublished -> {
                        tvUpdateStatus.text = outcome.detail
                    }
                    is AppUpdateManager.CheckOutcome.Error -> {
                        tvUpdateStatus.text = outcome.message
                    }
                }
            }
        }
    }

    private fun triggerDownloadAndInstall() {
        val rel = latestRelease ?: return
        if (!AppUpdateManager.canRequestPackageInstalls(this)) {
            Toast.makeText(this, "Izinkan pasang aplikasi dari sumber ini lalu tekan Unduh lagi.", Toast.LENGTH_LONG).show()
            AppUpdateManager.buildInstallUnknownSourcesIntent(this)?.let { startActivity(it) }
            return
        }

        btnCheckUpdate.isEnabled = false
        btnInstallUpdate.isEnabled = false
        progressUpdateCheck.visibility = View.VISIBLE
        progressUpdateCheck.isIndeterminate = false
        progressUpdateCheck.progress = 0
        tvUpdateStatus.text = "Mengunduh ${rel.apkAssetName ?: rel.tagName}..."

        AppUpdateManager.downloadApkForInstall(this, rel) { state ->
            runOnUiThread {
                when (state) {
                    is AppUpdateManager.DownloadState.Progress -> {
                        progressUpdateCheck.progress = state.percent
                        tvUpdateStatus.text = "Mengunduh pembaruan: ${state.percent}%"
                    }
                    is AppUpdateManager.DownloadState.ReadyToInstall -> {
                        progressUpdateCheck.visibility = View.GONE
                        btnCheckUpdate.isEnabled = true
                        btnInstallUpdate.isEnabled = true
                        tvUpdateStatus.text = state.message
                        AppUpdateManager.launchApkInstaller(this, state.apkFile)
                    }
                    is AppUpdateManager.DownloadState.Failed -> {
                        progressUpdateCheck.visibility = View.GONE
                        btnCheckUpdate.isEnabled = true
                        btnInstallUpdate.isEnabled = true
                        tvUpdateStatus.text = state.reason
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
