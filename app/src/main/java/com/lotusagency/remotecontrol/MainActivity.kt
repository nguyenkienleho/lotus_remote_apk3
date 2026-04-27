package com.lotusagency.remotecontrol

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val PROJECTION_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "Lotus Remote Control"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        val tvInfo = TextView(this).apply {
            text = "Device ID: ${Config.DEVICE_ID}\nServer: ${Config.SERVER_URL.take(50)}..."
            textSize = 12f
            setPadding(0, 0, 0, 32)
            setTextColor(0xFF888888.toInt())
        }

        val btnStart = Button(this).apply {
            text = "START REMOTE"
            textSize = 16f
        }
        btnStart.setOnClickListener { requestScreenCapture() }

        val btnStop = Button(this).apply {
            text = "STOP"
            textSize = 14f
        }
        btnStop.setOnClickListener {
            stopService(Intent(this@MainActivity, RemoteService::class.java))
            Toast.makeText(this@MainActivity, "Stopped", Toast.LENGTH_SHORT).show()
        }

        layout.addView(title)
        layout.addView(tvInfo)
        layout.addView(btnStart)
        layout.addView(Space(this).apply { minimumHeight = 16 })
        layout.addView(btnStop)
        scroll.addView(layout)
        setContentView(scroll)
    }

    private fun requestScreenCapture() {
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(pm.createScreenCaptureIntent(), PROJECTION_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PROJECTION_CODE && resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, RemoteService::class.java).apply {
                putExtra("projection_result", resultCode)
                putExtra("projection_data", data)
            }
            startForegroundService(intent)
            Toast.makeText(this, "Lotus Remote running!", Toast.LENGTH_LONG).show()
            finish()
        } else {
            Toast.makeText(this, "Permission required!", Toast.LENGTH_SHORT).show()
        }
    }
}
