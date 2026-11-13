package com.maichimod.prototype

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.CompoundButton
import android.widget.Button
import android.widget.FrameLayout
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ScrollView
import android.view.ContextThemeWrapper
import com.maichimod.prototype.transport.WiredLink

internal class ConnectionSettingsView(
    page: View,
    private val currentOptions: () -> ConnectionOptions,
    private val onSave: (ConnectionOptions) -> Unit,
    onRetry: () -> Unit,
    private val onThemeSelected: (ThemeColor) -> Unit = {},
    private val onSimulateCall: () -> Unit = {},
) {
    private val context = page.context
    private val backdrop = page.rootView.findViewById<FrameLayout>(R.id.app_root)
    private var savedForeground: Drawable? = null
    private val inflater = LayoutInflater.from(context)
    private val connectionStatus = page.findViewById<TextView>(R.id.settings_connection_status)
    private val ethernetDetails = page.findViewById<TextView>(R.id.ethernet_details)
    private val retryEthernet = page.findViewById<View>(R.id.retry_ethernet)
    private val autoHangUp = page.findViewById<CompoundButton>(R.id.auto_hang_up_after_unlock)
    private val sections = listOf(R.id.local_section, R.id.peer_section, R.id.volume_section, R.id.appearance_section).map { page.findViewById<View>(it) }
    private val sectionTabs = page.findViewById<RadioGroup>(R.id.settings_tabs)
    private val scroll = page.findViewById<ScrollView>(R.id.settings_scroll)
    private val tabIds = listOf(R.id.settings_tab_local, R.id.settings_tab_devices, R.id.settings_tab_sound, R.id.settings_tab_appearance)
    private var dialog: AlertDialog? = null
    private var dialogField: ConnectionField? = null
    private val values = buildMap {
        val local = page.findViewById<ViewGroup>(R.id.local_parameters)
        val peer = page.findViewById<ViewGroup>(R.id.peer_parameters)
        // 门机编号与对应 IP 相邻；中心地址独立放在最后。
        for (field in ConnectionField.entries.filter { it != ConnectionField.CENTER_IP } + ConnectionField.CENTER_IP) {
            val parent = when (field) {
                ConnectionField.PEER_ID, ConnectionField.PEER_IP, ConnectionField.CENTER_IP,
                ConnectionField.BASEMENT_ONE_ID, ConnectionField.BASEMENT_ONE_IP,
                ConnectionField.BASEMENT_TWO_ID, ConnectionField.BASEMENT_TWO_IP -> peer
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
        val themes = page.findViewById<ViewGroup>(R.id.theme_choices)
        for (color in ThemeColor.entries) {
            val themedContext = ContextThemeWrapper(context, color.overlay)
            val choice = Button(themedContext).apply {
                id = View.generateViewId()
                setText(color.label)
                isAllCaps = false
                textSize = 16f
                minHeight = (58 * resources.displayMetrics.density).toInt()
                setPadding((16 * resources.displayMetrics.density).toInt(), 0, (16 * resources.displayMetrics.density).toInt(), 0)
                MedioraUi.applySurface(this, SurfaceRole.PRIMARY)
                setTextColor(themedContext.getColor(R.color.on_primary))
                isSelected = color == Appearance.current(context)
                if (isSelected) {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_theme_check, 0, 0, 0)
                    compoundDrawableTintList = ColorStateList.valueOf(themedContext.getColor(R.color.on_primary))
                }
                setOnClickListener { if (color != Appearance.current(context)) onThemeSelected(color) }
            }
            themes.addView(choice)
        }
        sectionTabs.setOnCheckedChangeListener { _, selected -> selectSection(tabIds.indexOf(selected)) }
        selectSection(0)
        page.findViewById<View>(R.id.connection_mode_button).setOnClickListener { editMode() }
        retryEthernet.setOnClickListener { onRetry() }
        autoHangUp.setOnCheckedChangeListener { _, checked ->
            val options = currentOptions()
            if (checked != options.autoHangUpAfterUnlock) onSave(options.copy(autoHangUpAfterUnlock = checked))
        }
    }

    fun selectedSection(): Int = tabIds.indexOf(sectionTabs.checkedRadioButtonId).coerceAtLeast(0)

    fun restoreSection(index: Int) {
        val selected = tabIds[index.coerceIn(tabIds.indices)]
        sectionTabs.check(selected)
        sectionTabs.post {
            val tab = sectionTabs.findViewById<View>(selected)
            tab.requestRectangleOnScreen(Rect(0, 0, tab.width, tab.height), true)
        }
    }

    private fun selectSection(index: Int) {
        sections.forEachIndexed { position, section -> section.visibility = if (position == index) View.VISIBLE else View.GONE }
        scroll.scrollTo(0, 0)
    }

    fun renderOptions(options: ConnectionOptions) {
        autoHangUp.isChecked = options.autoHangUpAfterUnlock
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
        val previous = dialog ?: return
        dialog = null
        dialogField = null
        backdrop.foreground = savedForeground
        savedForeground = null
        previous.dismiss()
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
        MedioraUi.applySurface(input, SurfaceRole.INPUT)
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
                input.isSelected = false
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
                    input.isSelected = true
                    return@setOnClickListener
                }
                onSave(updated)
                editor.dismiss()
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
                input.isSelected = saved.getBoolean("error")
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
        val simulateCall = content.findViewById<View>(R.id.simulate_call)
        fun showMode(wired: Boolean) {
            simulateTimeout.visibility = if (wired) View.GONE else View.VISIBLE
            wiredHelp.visibility = if (wired) View.VISIBLE else View.GONE
            simulateCall.visibility = if (BuildConfig.DEBUG && !wired && !options.wired) View.VISIBLE else View.GONE
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
        simulateCall.setOnClickListener { picker.dismiss(); onSimulateCall() }
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
                backdrop.foreground = savedForeground
                savedForeground = null
            }
        }
        val density = context.resources.displayMetrics.density
        val header = FrameLayout(context).apply {
            clipChildren = false; clipToPadding = false
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            setBackgroundResource(R.drawable.field_row)
        }
        val heading = TextView(context).apply {
            text = if (field == null) context.getString(R.string.connection_mode) else context.getString(R.string.edit_field, context.getString(field.label))
            textSize = 20f
            setTextColor(context.getColor(R.color.text))
            typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 600, false) else Typeface.DEFAULT_BOLD
            includeFontPadding = false
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        header.addView(heading, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL; marginEnd = (48 * density).toInt()
        })
        val close = Button(context).apply {
            contentDescription = context.getString(R.string.close_dialog)
            minWidth = 0; minHeight = 0
            setPadding((7 * density).toInt(), (7 * density).toInt(), (7 * density).toInt(), (7 * density).toInt())
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_close, 0, 0, 0)
            compoundDrawableTintList = MedioraUi.buttonText(context, SurfaceRole.OUTLINE)
            MedioraUi.applySurface(this, SurfaceRole.CLOSE, addGutter = false)
            setOnClickListener { next.dismiss() }
        }
        header.addView(close, FrameLayout.LayoutParams((28 * density).toInt(), (28 * density).toInt()).apply {
            gravity = Gravity.END or Gravity.TOP
            topMargin = (-4 * density).toInt(); marginEnd = (-4 * density).toInt()
        })
        next.setCustomTitle(header)
        next.show()
        savedForeground = backdrop.foreground
        backdrop.foreground = ColorDrawable(context.getColor(R.color.dialog_scrim))
        val availableWidth = pageWidth() - (48 * density).toInt()
        next.window?.setLayout(minOf(availableWidth, (512 * density).toInt()), WindowManager.LayoutParams.WRAP_CONTENT)
        next.window?.setBackgroundDrawable(MedioraSurface(context, SurfaceRole.DIALOG))
        next.window?.decorView?.clipToOutline = false
        next.window?.setDimAmount(0f)
        for ((which, role) in listOf(AlertDialog.BUTTON_POSITIVE to SurfaceRole.PRIMARY, AlertDialog.BUTTON_NEGATIVE to SurfaceRole.OUTLINE)) {
            next.getButton(which).apply {
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
            MedioraUi.applySurface(this, role)
            setTextColor(MedioraUi.buttonText(context, role))
            textSize = 16f
            minWidth = (88 * density).toInt()
            minHeight = (50 * density).toInt()
            }
        }
    }

    private fun pageWidth(): Int = context.resources.displayMetrics.widthPixels
}
