package com.example.screenofftimer

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var adminBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var customInput: EditText

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun adminComponent() = ComponentName(this, AdminReceiver::class.java)

    private fun isAdminActive(): Boolean =
        getSystemService(DevicePolicyManager::class.java).isAdminActive(adminComponent())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(col)
        }
        setContentView(scroll)

        // aman untuk edge-to-edge (Android 15+)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(16), b.top + dp(16), dp(16), b.bottom + dp(16))
            insets
        }

        col.addView(TextView(this).apply {
            text = "Screen Off Timer"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "Layar mati otomatis setelah waktu yang dipilih."
            setPadding(0, dp(4), 0, dp(16))
        })

        statusText = TextView(this).apply {
            textSize = 40f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
        }
        col.addView(statusText)

        cancelBtn = Button(this).apply {
            text = "Batalkan timer"
            setOnClickListener {
                TimerService.stop(this@MainActivity)
                getSharedPreferences(TimerService.PREFS, MODE_PRIVATE).edit().clear().apply()
                refresh()
            }
        }
        col.addView(cancelBtn)

        adminBtn = Button(this).apply {
            text = "Aktifkan izin Device Admin (wajib sekali)"
            setOnClickListener { requestAdmin() }
        }
        col.addView(adminBtn)

        col.addView(TextView(this).apply {
            text = "Pilih durasi (menit)"
            setPadding(0, dp(20), 0, dp(8))
        })

        val grid = GridLayout(this).apply { columnCount = 3 }
        for (m in 10..120 step 10) {
            val b = Button(this).apply {
                text = "$m"
                setOnClickListener { startTimer(m) }
            }
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(b, lp)
        }
        col.addView(grid)

        col.addView(TextView(this).apply {
            text = "Custom (menit)"
            setPadding(0, dp(20), 0, dp(8))
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        customInput = EditText(this).apply {
            hint = "contoh: 45"
            inputType = InputType.TYPE_CLASS_NUMBER
            maxLines = 1
        }
        row.addView(customInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "Mulai"
            setOnClickListener {
                startTimer(customInput.text.toString().toIntOrNull() ?: 0)
            }
        })
        col.addView(row)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestAdmin() {
        val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent())
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Dibutuhkan supaya app bisa mematikan layar saat timer habis."
            )
        }
        startActivity(i)
    }

    private fun startTimer(minutes: Int) {
        if (minutes < 1 || minutes > 1440) {
            Toast.makeText(this, "Isi menit 1 - 1440", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isAdminActive()) {
            Toast.makeText(this, "Aktifkan izin Device Admin dulu", Toast.LENGTH_LONG).show()
            requestAdmin()
            return
        }
        TimerService.start(this, minutes)
        Toast.makeText(this, "Timer $minutes menit aktif", Toast.LENGTH_SHORT).show()
        // tulis awal supaya UI langsung update
        getSharedPreferences(TimerService.PREFS, MODE_PRIVATE).edit()
            .putLong(TimerService.KEY_END, System.currentTimeMillis() + minutes * 60_000L)
            .putInt(TimerService.KEY_TOTAL, minutes)
            .apply()
        refresh()
    }

    private fun refresh() {
        val end = getSharedPreferences(TimerService.PREFS, MODE_PRIVATE)
            .getLong(TimerService.KEY_END, 0L)
        val left = end - System.currentTimeMillis()
        if (left > 0) {
            val s = left / 1000
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            statusText.text = if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
            else String.format(Locale.US, "%02d:%02d", m, sec)
            cancelBtn.isEnabled = true
        } else {
            statusText.text = "Tidak aktif"
            statusText.textSize = 24f
            cancelBtn.isEnabled = false
        }
        if (left > 0) statusText.textSize = 40f
        adminBtn.visibility = if (isAdminActive()) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }
}
