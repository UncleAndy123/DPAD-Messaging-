package org.example.dpadmessaging

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ThreadListAdapter
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        // no-op for now
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.thread_list)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ThreadListAdapter(generateDummyThreads())
        recyclerView.adapter = adapter

        // request SMS permissions at runtime
        requestSmsPermissions()

        // simple send UI for demo
        val sendBtn: Button = findViewById(R.id.send_btn)
        val toField: EditText = findViewById(R.id.to_field)
        val msgField: EditText = findViewById(R.id.msg_field)
        sendBtn.setOnClickListener {
            // TODO: implement send using SmsManager and store thread in Room
        }
    }

    private fun requestSmsPermissions() {
        val needed = listOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.WRITE_SMS,
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissionRequest.launch(needed.toTypedArray())
    }

    // Make sure DPAD navigation works: handle up/down/enter for focus movement
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> return moveFocus(1)
                KeyEvent.KEYCODE_DPAD_UP -> return moveFocus(-1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    currentFocus?.performClick()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun moveFocus(delta: Int): Boolean {
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return false
        val first = lm.findFirstVisibleItemPosition()
        val focused = recyclerView.focusedChild
        var idx = if (focused == null) first else recyclerView.getChildAdapterPosition(focused)
        if (idx == RecyclerView.NO_POSITION) idx = 0
        val next = (idx + delta).coerceIn(0, adapter.itemCount - 1)
        val vh = recyclerView.findViewHolderForAdapterPosition(next)
        vh?.itemView?.requestFocus()
        recyclerView.smoothScrollToPosition(next)
        return true
    }

    private fun generateDummyThreads(): List<ThreadItem> {
        return List(10) { i -> ThreadItem(id = i.toLong(), address = "+1555000${i}", snippet = "Hello #${i}") }
    }
}
