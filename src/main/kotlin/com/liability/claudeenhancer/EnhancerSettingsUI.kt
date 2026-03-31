package com.liability.claudeenhancer

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class EnhancerSettingsUI : Configurable {

    private val settings = EnhancerSettings.getInstance()

    // ── Form fields ──────────────────────────────────────────────────────────

    private val endpointField     = JBTextField()
    private val apiKeyField       = JBPasswordField()
    private val claudePathField   = JBTextField()
    private val modelField        = JBTextField()
    private val apiStyleCombo     = JComboBox(ApiStyle.entries.toTypedArray())
    private val maxContextSpinner = JSpinner(SpinnerNumberModel(
        settings.state.maxContextChars, 100, 20_000, 100
    ))

    private val includeGitCheck       = JCheckBox("Include recent git commits")
    private val includeClaudeMdCheck  = JCheckBox("Include CLAUDE.md content")
    private val includeOpenFilesCheck = JCheckBox("Include open file / selection")

    private val systemPromptArea = JBTextArea(12, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    // Labels whose visibility we toggle with the style selector.
    private val endpointLabel   = JBLabel("API endpoint:")
    private val apiKeyLabel     = JBLabel("API key:")
    private val claudePathLabel = JBLabel("Claude CLI path:")

    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Claude Prompt Enhancer"

    override fun createComponent(): JComponent {
        apiStyleCombo.addActionListener { updateFieldVisibility() }

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("API style:"), apiStyleCombo, 1, false)
            .addLabeledComponent(endpointLabel,   endpointField,   1, false)
            .addLabeledComponent(apiKeyLabel,     apiKeyField,     1, false)
            .addLabeledComponent(claudePathLabel, claudePathField, 1, false)
            .addLabeledComponent(JBLabel("Model:"), modelField,    1, false)
            .addLabeledComponent(JBLabel("Max context chars:"), maxContextSpinner, 1, false)
            .addSeparator()
            .addComponent(JLabel("Context to include:"))
            .addComponent(includeGitCheck)
            .addComponent(includeClaudeMdCheck)
            .addComponent(includeOpenFilesCheck)
            .addSeparator()
            .addLabeledComponent(
                JBLabel("Enhancement system prompt:"),
                JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    add(JButton("Reset to default").apply {
                        addActionListener { systemPromptArea.text = EnhancerState.DEFAULT_SYSTEM_PROMPT }
                    })
                },
                1, false
            )
            .addComponentFillVertically(
                JBScrollPane(systemPromptArea).apply {
                    border = BorderFactory.createCompoundBorder(
                        JBUI.Borders.empty(4),
                        BorderFactory.createLineBorder(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
                    )
                },
                0
            )
            .panel

        updateFieldVisibility()
        return JPanel(BorderLayout()).also {
            it.add(form, BorderLayout.CENTER)
            panel = it
        }
    }

    private fun updateFieldVisibility() {
        val isCli = apiStyleCombo.selectedItem == ApiStyle.CLI
        endpointLabel.isVisible   = !isCli
        endpointField.isVisible   = !isCli
        apiKeyLabel.isVisible     = !isCli
        apiKeyField.isVisible     = !isCli
        claudePathLabel.isVisible = isCli
        claudePathField.isVisible = isCli
        panel?.revalidate()
    }

    override fun isModified(): Boolean {
        val s = settings.state
        val keyChars = apiKeyField.password
        val keyModified = String(keyChars) != (settings.apiKey ?: "")
        java.util.Arrays.fill(keyChars, '\u0000')
        return endpointField.text              != s.endpoint              ||
               keyModified                                                ||
               claudePathField.text            != s.claudePath            ||
               modelField.text                 != s.model                 ||
               apiStyleCombo.selectedItem      != s.apiStyle              ||
               (maxContextSpinner.value as Int) != s.maxContextChars      ||
               includeGitCheck.isSelected       != s.includeGitContext    ||
               includeClaudeMdCheck.isSelected  != s.includeClaudeMd     ||
               includeOpenFilesCheck.isSelected != s.includeOpenFiles     ||
               systemPromptArea.text            != s.systemPrompt
    }

    override fun apply() {
        val s = settings.state
        s.endpoint        = endpointField.text.trim()
        s.claudePath      = claudePathField.text.trim().ifBlank { "claude" }
        s.model           = modelField.text.trim()
        s.apiStyle        = apiStyleCombo.selectedItem as ApiStyle
        s.maxContextChars = maxContextSpinner.value as Int
        s.includeGitContext   = includeGitCheck.isSelected
        s.includeClaudeMd     = includeClaudeMdCheck.isSelected
        s.includeOpenFiles    = includeOpenFilesCheck.isSelected
        s.systemPrompt    = systemPromptArea.text

        val keyChars = apiKeyField.password
        settings.apiKey = String(keyChars).takeIf { it.isNotBlank() }
        java.util.Arrays.fill(keyChars, '\u0000')
    }

    override fun reset() {
        val s = settings.state
        endpointField.text         = s.endpoint
        apiKeyField.text           = settings.apiKey ?: ""
        claudePathField.text       = s.claudePath
        modelField.text            = s.model
        apiStyleCombo.selectedItem = s.apiStyle
        maxContextSpinner.value    = s.maxContextChars
        includeGitCheck.isSelected     = s.includeGitContext
        includeClaudeMdCheck.isSelected = s.includeClaudeMd
        includeOpenFilesCheck.isSelected = s.includeOpenFiles
        systemPromptArea.text      = s.systemPrompt
        updateFieldVisibility()
    }
}
