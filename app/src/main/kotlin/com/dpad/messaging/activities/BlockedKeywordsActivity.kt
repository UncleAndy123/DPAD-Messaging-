package com.dpad.messaging.activities

import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dpad.messaging.App
import com.dpad.messaging.R
import com.dpad.messaging.databinding.ActivityBlockedKeywordsBinding
import com.dpad.messaging.models.BlockedKeyword
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Blocked Keywords screen.
 *
 * Programmatic rows — same pattern as SettingsActivity.
 * First row is always "Add Keyword" (navRow).
 * Each existing keyword is a row that shows a delete dialog on ENTER/click.
 */
class BlockedKeywordsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockedKeywordsBinding

    private var paddingH = 0
    private var paddingV = 0
    private var rowMinH  = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockedKeywordsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        paddingH = resources.getDimensionPixelSize(R.dimen.padding_medium)
        paddingV = resources.getDimensionPixelSize(R.dimen.padding_small)
        rowMinH  = resources.getDimensionPixelSize(R.dimen.conversation_item_height)

        binding.btnBack.setOnClickListener { finish() }
        loadKeywords()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_STAR) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }

    // ─── Data ─────────────────────────────────────────────────────────────────

    private fun loadKeywords() {
        lifecycleScope.launch {
            val keywords = withContext(Dispatchers.IO) {
                App.get().database.blockedKeywordsDao().getAll()
            }
            buildRows(keywords)
        }
    }

    // ─── Row population ───────────────────────────────────────────────────────

    private fun buildRows(keywords: List<BlockedKeyword>) {
        val c = binding.keywordsContainer
        c.removeAllViews()

        // "Add keyword" is always the first row
        navRow(c, getString(R.string.add_keyword), "") { showAddDialog() }

        if (keywords.isEmpty()) {
            val tv = TextView(this).apply {
                text = getString(R.string.no_blocked_keywords)
                setTextColor(getColor(R.color.conversationDate))
                setPadding(paddingH, paddingV * 2, paddingH, paddingV * 2)
                isFocusable = false
            }
            c.addView(tv)
        } else {
            keywords.forEach { kw -> keywordRow(c, kw) }
        }
    }

    // ─── Dialogs ──────────────────────────────────────────────────────────────

    private fun showAddDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.enter_keyword)
            setTextColor(getColor(R.color.colorOnBackground))
            setBackgroundResource(R.drawable.compose_input_bg)
            setPadding(paddingH, paddingV, paddingH, paddingV)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_keyword)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val kw = input.text?.toString()?.trim()
                if (!kw.isNullOrBlank()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        App.get().database.blockedKeywordsDao()
                            .insert(BlockedKeyword(keyword = kw))
                        withContext(Dispatchers.Main) { loadKeywords() }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteDialog(kw: BlockedKeyword) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_keyword_confirm))
            .setTitle("\"${kw.keyword}\"")
            .setPositiveButton(R.string.yes) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    App.get().database.blockedKeywordsDao().delete(kw.id)
                    withContext(Dispatchers.Main) { loadKeywords() }
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    // ─── Row builders ─────────────────────────────────────────────────────────

    private fun keywordRow(container: LinearLayout, kw: BlockedKeyword) {
        // Build a delete icon on the right (an "×" text label)
        val tvDelete = TextView(this).apply {
            text = "×"
            setTextColor(getColor(R.color.colorSecondary))
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.text_size_large)
            )
            isFocusable = false
        }
        val tvKeyword = TextView(this).apply {
            text = kw.keyword
            setTextColor(getColor(R.color.colorOnBackground))
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.text_size_normal)
            )
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val row = buildRow().apply {
            addView(tvKeyword)
            addView(tvDelete)
            setOnClickListener { showDeleteDialog(kw) }
            setOnKeyListener { _, keyCode, event ->
                if (isConfirmKey(keyCode) && event.action == KeyEvent.ACTION_DOWN) {
                    performClick(); true
                } else false
            }
        }
        container.addView(row)
    }

    private fun navRow(
        container: LinearLayout,
        label: String,
        summary: String,
        onClick: () -> Unit
    ) {
        val tvChevron = TextView(this).apply {
            text = "›"
            setTextColor(getColor(R.color.colorOnBackground))
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.text_size_large)
            )
            isFocusable = false
        }
        val textCol = buildTextColumn(label, summary)
        val row = buildRow().apply {
            addView(textCol)
            addView(tvChevron)
            setOnClickListener { onClick() }
            setOnKeyListener { _, keyCode, event ->
                if (isConfirmKey(keyCode) && event.action == KeyEvent.ACTION_DOWN) {
                    performClick(); true
                } else false
            }
        }
        container.addView(row)
    }

    private fun buildTextColumn(label: String, summary: String): LinearLayout {
        val tvLabel = TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.colorOnBackground))
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.text_size_normal)
            )
        }
        val tvSummary = TextView(this).apply {
            text = summary
            setTextColor(getColor(R.color.conversationDate))
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.text_size_small)
            )
            visibility = if (summary.isBlank()) View.GONE else View.VISIBLE
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            addView(tvLabel)
            addView(tvSummary)
        }
    }

    private fun buildRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = rowMinH
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setBackgroundResource(R.drawable.item_focusable_bg)
        setPadding(paddingH, paddingV, paddingH, paddingV)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun isConfirmKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
}
