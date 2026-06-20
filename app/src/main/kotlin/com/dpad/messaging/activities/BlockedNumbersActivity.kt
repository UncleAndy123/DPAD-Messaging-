package com.dpad.messaging.activities

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.dpad.messaging.App
import com.dpad.messaging.R
import com.dpad.messaging.databinding.ActivityBlockedNumbersBinding
import com.dpad.messaging.helpers.ThemeManager
import com.dpad.messaging.models.BlockedNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlockedNumbersActivity : BaseActivity() {

    private lateinit var binding: ActivityBlockedNumbersBinding

    private var paddingH = 0
    private var paddingV = 0
    private var rowMinH  = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyAccentColor(this)
        binding = ActivityBlockedNumbersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        paddingH = resources.getDimensionPixelSize(R.dimen.padding_medium)
        paddingV = resources.getDimensionPixelSize(R.dimen.padding_small)
        rowMinH  = resources.getDimensionPixelSize(R.dimen.conversation_item_height)

        binding.btnBack.setOnClickListener { finish() }
        binding.tvToolbarTitle.text = getString(R.string.manage_blocked_numbers)
        loadNumbers()
    }

    private fun loadNumbers() {
        lifecycleScope.launch {
            val numbers = withContext(Dispatchers.IO) {
                App.get().database.blockedNumbersDao().getAll()
            }
            buildRows(numbers)
        }
    }

    private fun buildRows(numbers: List<BlockedNumber>) {
        val c = binding.keywordsContainer
        c.removeAllViews()

        navRow(c, getString(R.string.add_number), "") { showAddDialog() }

        if (numbers.isEmpty()) {
            val tv = TextView(this).apply {
                text = getString(R.string.no_blocked_numbers)
                setTextColor(getColor(R.color.conversationDate))
                setPadding(paddingH, paddingV * 2, paddingH, paddingV * 2)
                isFocusable = false
            }
            c.addView(tv)
        } else {
            numbers.forEach { num -> numberRow(c, num) }
        }
    }

    private fun showAddDialog() {
            val input = EditText(this).apply {
            hint = getString(R.string.enter_number)
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
                        App.get().database.blockedNumbersDao()
                            .insert(BlockedNumber(number = kw))
                        withContext(Dispatchers.Main) { loadNumbers() }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteDialog(kw: BlockedNumber) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_number_confirm))
            .setTitle("\"${kw.number}\"")
            .setPositiveButton(R.string.yes) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    App.get().database.blockedNumbersDao().delete(kw.id)
                    withContext(Dispatchers.Main) { loadNumbers() }
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun numberRow(container: LinearLayout, kw: BlockedNumber) {
        val tvDelete = TextView(this).apply {
            text = "×"
            setTextColor(accentStateTextColors())
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.text_size_large)
            )
            isFocusable = false
        }
        val tvKeyword = TextView(this).apply {
            text = kw.number
            setTextColor(accentStateTextColors())
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
            setTextColor(accentStateTextColors())
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
            setTextColor(accentStateTextColors())
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.text_size_normal)
            )
        }
        val tvSummary = TextView(this).apply {
            text = summary
            setTextColor(accentStateTextColors())
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.text_size_small)
            )
            visibility = if (summary.isBlank()) View.GONE else View.VISIBLE
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
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
        backgroundTintList = ColorStateList.valueOf(ThemeManager.accentColor(this@BlockedNumbersActivity))
        setPadding(paddingH, paddingV, paddingH, paddingV)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun accentStateTextColors(): ColorStateList {
        val accent = ThemeManager.accentColor(this)
        return ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(accent, getColor(R.color.colorOnBackground))
        )
    }

    private fun isConfirmKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
}
