package com.liability.claudeenhancer

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task

private val LOG = logger<EnhancePromptAction>()

/**
 * Entry point for the Shift+Ctrl+E shortcut.
 *
 * Flow:
 *  1. Read current terminal input (best-effort via [TerminalInputAccessor])
 *  2. Collect ambient project context ([ContextCollector]) — runs off-EDT
 *     because git4idea's history API must not be called on the EDT.
 *  3. Open [EnhancePromptDialog] — which auto-starts the LLM call
 *  4. On "Insert": write enhanced text back to the terminal via [TerminalInputAccessor]
 */
class EnhancePromptAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: run {
            LOG.warn("EnhancePromptAction fired without a project")
            return
        }

        val rawPrompt = TerminalInputAccessor.readInput(event)

        // Context collection MUST run off the EDT: git4idea's history API
        // tries to reach the built-in server, which blocks if called on EDT.
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Collecting project context…", false
        ) {
            private var context = ""

            override fun run(indicator: ProgressIndicator) {
                context = try {
                    ContextCollector(project).collect()
                } catch (e: Exception) {
                    LOG.warn("Context collection failed (non-fatal): ${e.message}")
                    ""
                }
            }

            private fun openDialog(ctx: String) {
                ApplicationManager.getApplication().invokeLater {
                    EnhancePromptDialog(
                        project       = project,
                        initialPrompt = rawPrompt,
                        context       = ctx,
                        onInsert      = { TerminalInputAccessor.writeInput(event, it) },
                    ).show()
                }
            }

            override fun onSuccess() = openDialog(context)

            override fun onThrowable(error: Throwable) {
                LOG.error("Context collection threw unexpectedly", error)
                openDialog("")
            }
        })
    }

    /** Keep the action enabled only when a project is open. */
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }
}
