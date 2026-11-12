package com.maichimod.prototype

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import com.maichimod.prototype.transport.WiredLink

internal class ConnectionSettingsView(
    page: View,
    private val currentOptions: () -> ConnectionOptions,
    private val onSave: (ConnectionOptions) -> Unit,
    onRetry: () -> Unit,
) {
    private val context = page.context
    private val inflater = LayoutInflater.from(context)
    private val connectionStatus = page.findViewById<TextView>(R.id.settings_connection_status)
    private val ethernetDetails = page.findViewById<TextView>(R.id.ethernet_details)
    private val retryEthernet = page.findViewById<View>(R.id.retry_ethernet)
    private var dialog: AlertDialog? = null
    private var dialogField: ConnectionField? = null
    private val values = buildMap {
        val local = page.findViewById<LinearLayout>(R.id.local_parameters)
        val peer = page.findViewById<LinearLayout>(R.id.peer_parameters)
        for (field in ConnectionField.entries) {
            val parent = when (field) {
                ConnectionField.PEER_ID, ConnectionField.PEER_IP, ConnectionField.CENTER_IP -> peer
                else -> local
            }
            val row = inflater.inflate(R.layout.settings_field_row, parent, false)
            row.findViewById<TextView>(R.id.setting_label).setText(field.label)
            row.findViewById<View>(R.id.edit_setting).apply {
                contentDescription = context.getString(R.string.edit_field, context.getString(field.label))
                setOnClickListener { editField(field) }
            }
            parent.addView(row)
            put(field, row.findViewById<TextView>(R.id.setting_value))
        }
    }

    init {
        page.findViewById<View>(R.id.connection_mode_button).setOnClickListener { editMode() }
        retryEthernet.setOnClickListener { onRetry() }
    }

    fun renderOptions(options: ConnectionOptions) {
        for ((field, label) in values) {
            val value = field.display(options[field])
            label.text = value.ifEmpty { context.getString(field.emptyValue) }
            label.setTextColor(context.getColor(if (value.isEmpty()) R.color.muted_text else R.color.text))
        }
    }

    fun renderConnection(status: Int, color: Int, link: WiredLink?, canRetry: Boolean) {
        connectionStatus.text = if (currentOptions().wired) {
            context.getString(R.string.wired_mode_status, context.getString(R.string.wired_mode), context.getString(status))
        } else {
            context.getString(status)
        }
        connectionStatus.compoundDrawableTintList = ColorStateList.valueOf(context.getColor(color))
        retryEthernet.visibility = if (canRetry) View.VISIBLE else View.GONE
        ethernetDetails.visibility = if (link == null) View.GONE else View.VISIBLE
        if (link != null) {
            ethernetDetails.text = context.getString(
                R.string.ethernet_values, link.address.hostAddress, maskForPrefix(link.prefixLength),
                link.gateway?.hostAddress ?: context.getString(R.string.unavailable),
            )
        }
    }

    fun dismissDialog() {
        dialog?.dismiss()
    }

    fun saveDialogState(): Bundle? {
        val current = dialog?.takeIf { it.isShowing } ?: return null
        val field = dialogField
        return Bundle().apply {
            putString("field", field?.name)
            if (field != null) {
                val input = current.findViewById<EditText>(R.id.field_input)
                putString("value", input.text.toString())
                putInt("selection_start", input.selectionStart)
                putInt("selection_end", input.selectionEnd)
                putBoolean("error", current.findViewById<View>(R.id.field_error).visibility == View.VISIBLE)
            } else {
                putBoolean("wired", current.findViewById<RadioGroup>(R.id.mode_group).checkedRadioButtonId == R.id.option_wired)
                putBoolean("simulate_timeout", current.findViewById<CheckBox>(R.id.simulate_timeout).isChecked)
            }
        }
    }

    fun restoreDialogState(saved: Bundle?) {
        if (saved == null) return
        val fieldName = saved.getString("field")
        if (fieldName != null) {
            val field = ConnectionField.entries.firstOrNull { it.name == fieldName } ?: return
            editField(field, saved)
        } else {
            editMode(saved)
        }
    }

    @Suppress("DEPRECATION")
    private fun editField(field: ConnectionField, saved: Bundle? = null) {
        val content = inflater.inflate(R.layout.dialog_connection_field, null)
        content.findViewById<TextView>(R.id.field_help).setText(field.help)
        val input = content.findViewById<EditText>(R.id.field_input)
        val error = content.findViewById<TextView>(R.id.field_error)
        input.setHint(field.label)
        input.setText(saved?.getString("value") ?: field.display(currentOptions()[field]))
        input.setSelectAllOnFocus(saved == null)
        error.setText(field.error)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                error.visibility = View.GONE
            }
        })
        val editor = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.edit_field, context.getString(field.label)))
            .setView(content)
            .setNegativeButton(R.string.cancel_setting, null)
            .setPositiveButton(R.string.save_setting, null)
            .create()
        editor.setOnShowListener {
            val save = editor.getButton(AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val updated = currentOptions().withField(field, input.text.toString())
                if (updated == null) {
                    error.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                editor.dismiss()
                onSave(updated)
            }
            input.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) save.performClick() else false
            }
            input.requestFocus()
            if (saved != null) {
                val length = input.length()
                input.setSelection(
                    saved.getInt("selection_start", length).coerceIn(0, length),
                    saved.getInt("selection_end", length).coerceIn(0, length),
                )
                error.visibility = if (saved.getBoolean("error")) View.VISIBLE else View.GONE
            }
        }
        editor.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        showDialog(editor, field)
    }

    private fun editMode(saved: Bundle? = null) {
        val options = currentOptions()
        val content = inflater.inflate(R.layout.dialog_connection_mode, null)
        val modes = content.findViewById<RadioGroup>(R.id.mode_group)
        val simulateTimeout = content.findViewById<CheckBox>(R.id.simulate_timeout)
        val wiredHelp = content.findViewById<View>(R.id.wired_mode_help)
        fun showMode(wired: Boolean) {
            simulateTimeout.visibility = if (wired) View.GONE else View.VISIBLE
            wiredHelp.visibility = if (wired) View.VISIBLE else View.GONE
        }
        val wired = saved?.getBoolean("wired") ?: options.wired
        simulateTimeout.isChecked = saved?.getBoolean("simulate_timeout") ?: options.simulateTimeout
        modes.check(if (wired) R.id.option_wired else R.id.option_sample)
        showMode(wired)
        modes.setOnCheckedChangeListener { _, selected -> showMode(selected == R.id.option_wired) }
        val picker = AlertDialog.Builder(context)
            .setTitle(R.string.connection_mode)
            .setView(content)
            .setNegativeButton(R.string.cancel_setting, null)
            .setPositiveButton(R.string.save_setting) { _, _ ->
                onSave(currentOptions().copy(
                    wired = modes.checkedRadioButtonId == R.id.option_wired,
                    simulateTimeout = simulateTimeout.isChecked,
                ))
            }
            .create()
        showDialog(picker)
    }

    private fun showDialog(next: AlertDialog, field: ConnectionField? = null) {
        dismissDialog()
        dialog = next
        dialogField = field
        next.setOnDismissListener {
            if (dialog === next) {
                dialog = null
                dialogField = null
            }
        }
        next.show()
        next.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(context.getColor(R.color.secondary_text))
    }
}
