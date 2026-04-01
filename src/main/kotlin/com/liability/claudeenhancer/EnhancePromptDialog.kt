package com.liability.claudeenhancer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapper.IdeModalityType
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.KeyStroke

/**
 * Two-pane review dialog:
 *
 *  ┌─────────────────────────────────────────────────┐
 *  │  Type your rough prompt:                        │
 *  ├─────────────────────────────────────────────────┤
 *  │  Enhanced prompt (editable)            [↻] [⟳] │
 *  ├─────────────────────────────────────────────────┤
 *  │  Context: N chars · model: X   [Cancel] [Insert]│
 *  └─────────────────────────────────────────────────┘
 *
 * The user types a rough idea in the top pane, hits Enhance ↻ (or it runs
 * automatically), edits the result if needed, then clicks Insert.
 */
class EnhancePromptDialog(
    private val project: Project,
    initialPrompt: String,
    private val context: String,
    private val onInsert: (String) -> Unit,
) : DialogWrapper(project, true, IdeModalityType.MODELESS) {

    private val originalArea  = JBTextArea(initialPrompt, 6, 70).apply {
        lineWrap = true; wrapStyleWord = true
    }
    private val enhancedArea  = JBTextArea(6, 70).apply {
        lineWrap = true; wrapStyleWord = true; isEditable = true
    }
    private val enhanceButton = JButton("Enhance ↻")
    private val spinner       = AsyncProcessIcon("enhancing").apply { isVisible = false }
    private val elapsedLabel  = JBLabel().apply {
        foreground = UIUtil.getLabelDisabledForeground()
        isVisible = false
    }

    /** Timer that updates the elapsed label every second while loading. */
    private var elapsedTimer: javax.swing.Timer? = null
    private var enhanceStartMs = 0L

    val enhancedText: String get() = enhancedArea.text

    init {
        title = "Claude Prompt Enhancer"
        setOKButtonText("Insert into terminal")
        init()

        // Ctrl+Enter / Cmd+Enter inserts into terminal from anywhere in the dialog.
        val insertKey = if (System.getProperty("os.name", "").lowercase().contains("mac"))
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, ActionEvent.META_MASK)
        else
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, ActionEvent.CTRL_MASK)

        rootPane.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(insertKey, "insertAction")
        rootPane.actionMap.put("insertAction", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) { doOKAction() }
        })

        // Auto-focus the input area so the user can start typing immediately.
        ApplicationManager.getApplication().invokeLater {
            originalArea.requestFocusInWindow()
            if (initialPrompt.isNotBlank()) {
                originalArea.selectAll()
                runEnhancement()
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        val inputLabel = JBLabel("Type your rough prompt:").apply {
            border = JBUI.Borders.emptyBottom(2)
        }
        val originalPanel = JPanel(BorderLayout(0, 4)).apply {
            add(inputLabel, BorderLayout.NORTH)
            add(JBScrollPane(originalArea), BorderLayout.CENTER)
        }

        val enhancedPanel = JPanel(BorderLayout(0, 4)).apply {
            val header = JPanel(GridBagLayout()).apply {
                val gbc = GridBagConstraints().apply {
                    fill = GridBagConstraints.HORIZONTAL; weightx = 1.0
                }
                add(JBLabel("Enhanced prompt (editable):"), gbc)
                gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0; gbc.gridx = 1
                add(elapsedLabel, gbc)
                gbc.gridx = 2
                add(spinner, gbc)
                gbc.gridx = 3
                add(enhanceButton, gbc)
            }
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(enhancedArea), BorderLayout.CENTER)
        }

        enhanceButton.addActionListener { runEnhancement() }

        val contextLabel = JBLabel(
            if (context.isBlank()) "No project context"
            else "Context: ${"%,d".format(context.length)} chars  ·  model: ${EnhancerSettings.getInstance().state.model}"
        ).apply {
            foreground = UIUtil.getLabelDisabledForeground()
            font = font.deriveFont(font.size2D - 1f)
            border = JBUI.Borders.emptyTop(4)
        }

        return JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(700, 440)
            add(JSplitPane(JSplitPane.VERTICAL_SPLIT, originalPanel, enhancedPanel).apply {
                resizeWeight = 0.35
                isContinuousLayout = true
            }, BorderLayout.CENTER)
            add(contextLabel, BorderLayout.SOUTH)
        }
    }

    override fun doOKAction() {
        val text = enhancedArea.text.trim()
        if (text.isBlank()) {
            Messages.showWarningDialog(project, "Enhanced prompt is empty.", "Cannot Insert")
            return
        }
        onInsert(text)
        super.doOKAction()
    }

    // ── Enhancement ───────────────────────────────────────────────────────────

    private fun setLoading(loading: Boolean) {
        enhanceButton.isEnabled = !loading
        spinner.isVisible = loading
        if (loading) spinner.resume() else spinner.suspend()
        enhancedArea.isEditable = !loading

        if (loading) {
            enhanceStartMs = System.currentTimeMillis()
            elapsedLabel.text = "0s "
            elapsedLabel.isVisible = true
            elapsedTimer = javax.swing.Timer(1000) {
                val secs = (System.currentTimeMillis() - enhanceStartMs) / 1000
                elapsedLabel.text = "${secs}s "
            }.also { it.start() }
        } else {
            elapsedTimer?.stop()
            elapsedTimer = null
            elapsedLabel.isVisible = false
        }
    }

    private fun runEnhancement() {
        val rawPrompt = originalArea.text.trim()
        if (rawPrompt.isBlank()) {
            Messages.showWarningDialog(project, "Please enter a prompt to enhance.", "Empty Prompt")
            return
        }

        // Confirm before wiping user edits.
        if (enhancedArea.text.isNotBlank()) {
            val overwrite = Messages.showYesNoDialog(
                project,
                "Re-running enhancement will replace your current edits. Continue?",
                "Re-enhance",
                Messages.getQuestionIcon()
            )
            if (overwrite != Messages.YES) return
        }

        val settings = EnhancerSettings.getInstance()
        val isCli    = settings.state.apiStyle == ApiStyle.CLI
        val key      = if (isCli) "" else {
            settings.apiKey?.takeIf { it.isNotBlank() } ?: run {
                Messages.showErrorDialog(
                    project,
                    "No API key configured.\nGo to Settings → Tools → Claude Prompt Enhancer.",
                    "Missing API Key"
                )
                return
            }
        }

        enhancedArea.text = ""
        setLoading(true)

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Enhancing prompt…", true
        ) {
            private var result: String? = null
            private var error: String?  = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val client = ModelClient(settings.state, key)
                    result = client.enhance(rawPrompt, context)
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }
            }

            override fun onSuccess() {
                setLoading(false)
                result?.let { enhancedArea.text = it } ?: run {
                    Messages.showErrorDialog(project, "Enhancement failed: $error", "API Error")
                }
            }

            override fun onCancel() {
                setLoading(false)
            }

            override fun onThrowable(error: Throwable) {
                setLoading(false)
            }
        })
    }
}
