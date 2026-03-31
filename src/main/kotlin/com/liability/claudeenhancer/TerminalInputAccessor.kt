package com.liability.claudeenhancer

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.Component
import java.awt.Container
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent

private val LOG = logger<TerminalInputAccessor>()
private val IS_MAC = System.getProperty("os.name", "").lowercase().contains("mac")
private val robot: Robot by lazy { Robot() }

/**
 * Reads and writes the active terminal's current input line.
 *
 * Strategy (tried in order):
 *
 * 1. **Reworked Terminal API** (IJ 2025.3+ / 253+):
 *    Uses the official `TerminalView` / `TerminalOutputModel` APIs and
 *    `TerminalView.sendText()` for writing.  This is the only strategy that
 *    works with the Reworked Terminal that became the default in 2025.2.
 *
 * 2. **Block terminal API** (IJ 2023.2–2025.1):
 *    `TerminalPromptController` exposes `getCommandText()` / `setCommandText()`
 *    directly, without any buffer scraping.
 *    Accessed via `TerminalPromptController.KEY` data key from the action event.
 *
 * 3. **Classic terminal API** (fallback):
 *    Walk the component tree of every tool window looking for a
 *    `ShellTerminalWidget`, then use `getTypedShellCommand()` to read and
 *    `ttyConnector.write("\u0015" + text)` (Ctrl+U + text) to write.
 *
 * 4. **Clipboard injection** (last resort):
 *    Ctrl+U via Robot, then paste the enhanced prompt from the clipboard.
 */
object TerminalInputAccessor {

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns the text the user has typed so far (not yet submitted). Empty if unreadable. */
    fun readInput(event: AnActionEvent): String {
        // Strategy 1: Reworked Terminal (IJ 253+) — official TerminalView API.
        readViaReworkedTerminal(event)?.let { return it }
        // Strategy 2a: block terminal via the action's own data context.
        readViaBlockTerminal(event)?.let { return it }
        // Strategy 2b: block terminal via the Terminal tool window's component data context.
        // This works when the global shortcut fires outside the terminal's own focus context.
        readViaBlockTerminalFromToolWindow(event)?.let { return it }
        // Strategy 3: classic JediTerm ShellTerminalWidget.
        readViaClassicTerminal(event)?.let { return it }
        // Strategy 4: any focused JTextComponent (e.g. custom plugin panels).
        readViaFocusedTextComponent()?.let { return it }
        LOG.warn("Could not read terminal input via any strategy")
        return ""
    }

    /**
     * Replace the current terminal input with [text] without submitting it.
     * Returns true on success.
     */
    fun writeInput(event: AnActionEvent, text: String): Boolean {
        if (writeViaReworkedTerminal(event, text)) return true
        if (writeViaBlockTerminal(event, text)) return true
        if (writeViaClassicTerminal(event, text)) return true
        // Clipboard fallback dispatched off EDT — contains Thread.sleep calls.
        ApplicationManager.getApplication().executeOnPooledThread { writeViaClipboard(text) }
        return true
    }

    // ── Strategy 1: Reworked Terminal (TerminalView, IJ 253+) ─────────────────

    /**
     * IJ 2025.3+ ships the official terminal API with [TerminalView] instances
     * accessible via [TerminalToolWindowTabsManager].  We use
     * `TerminalOutputModel.getLastCommandPrompt()` to read the current input
     * and `TerminalView.sendText()` to write.
     *
     * All access is via reflection so the plugin still loads on older IDEs.
     */
    private fun readViaReworkedTerminal(event: AnActionEvent): String? {
        val project = event.project ?: return null
        return try {
            val view = getActiveTerminalView(project) ?: return null

            // TerminalView.getOutputModel() → TerminalOutputModel
            val outputModel = view.javaClass.getMethod("getOutputModel").invoke(view)
                ?: return null

            // Try to get the current command text from the output model.
            // TerminalOutputModel exposes the current prompt/command via getCommandText()
            // or through the TerminalBlocksModel.
            val text = runCatching {
                outputModel.javaClass.getMethod("getCommandText").invoke(outputModel) as? String
            }.getOrNull()

            text?.takeIf { it.isNotBlank() }
        } catch (_: ClassNotFoundException) { null }
          catch (_: NoSuchMethodException) { null }
          catch (e: Exception) {
              LOG.debug("Reworked terminal read failed: ${e.message}")
              null
          }
    }

    private fun writeViaReworkedTerminal(event: AnActionEvent, text: String): Boolean {
        val project = event.project ?: return false
        return try {
            val view = getActiveTerminalView(project) ?: return false

            // Clear existing input with Ctrl+U, then send the new text.
            val sendText = view.javaClass.getMethod("sendText", String::class.java)
            sendText.invoke(view, "\u0015$text")
            true
        } catch (_: ClassNotFoundException) { false }
          catch (_: NoSuchMethodException) { false }
          catch (e: Exception) {
              LOG.debug("Reworked terminal write failed: ${e.message}")
              false
          }
    }

    /**
     * Locate the active [TerminalView] from the Terminal tool window.
     * Uses `TerminalToolWindowTabsManager` (253+) to get the selected tab's view.
     */
    private fun getActiveTerminalView(project: com.intellij.openapi.project.Project): Any? {
        val managerClass = Class.forName(
            "org.jetbrains.plugins.terminal.TerminalToolWindowTabsManager"
        )
        // TerminalToolWindowTabsManager.getInstance(project)
        val getInstance = managerClass.getMethod(
            "getInstance", com.intellij.openapi.project.Project::class.java
        )
        val manager = getInstance.invoke(null, project) ?: return null

        // manager.getActiveTab() → TerminalView?
        val getActiveTab = manager.javaClass.getMethod("getActiveTab")
        return getActiveTab.invoke(manager)
    }

    // ── Strategy 2: Block terminal (TerminalPromptController) ─────────────────

    /**
     * IJ 2023.2+ ships a "block" terminal with a clean prompt model.
     * `TerminalPromptController.KEY` is a DataKey whose value exposes
     * `getCommandText()` / `setCommandText(String)` on the EDT.
     */
    private fun readViaBlockTerminal(event: AnActionEvent): String? = try {
        val controllerClass = Class.forName(
            "org.jetbrains.plugins.terminal.block.prompt.TerminalPromptController"
        )
        val keyField = controllerClass.getDeclaredField("KEY").also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val key = keyField.get(null) as? com.intellij.openapi.actionSystem.DataKey<Any>
            ?: return null

        val controller = event.dataContext.getData(key) ?: return null
        val getCmd = controller.javaClass.getMethod("getCommandText")
        getCmd.invoke(controller) as? String
    } catch (_: ClassNotFoundException) { null }
      catch (e: Exception) { LOG.debug("Block terminal read failed: ${e.message}"); null }

    /**
     * Same as [readViaBlockTerminal] but obtains the data context from the
     * Terminal tool window's content component rather than from the action event.
     *
     * This is required for GoLand 2025+ where the block terminal's TerminalPromptController
     * is NOT propagated into the global action data context when the shortcut is a
     * keymap-level binding rather than an in-terminal action.
     *
     * Claude Code Beta uses the standard "Terminal" tool window (ID = "Terminal").
     */
    private fun readViaBlockTerminalFromToolWindow(event: AnActionEvent): String? {
        val project = event.project ?: return null
        return try {
            val tw = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
                ?: return null
            if (!tw.isVisible) return null
            val content = tw.contentManager.selectedContent ?: return null

            // Build a data context rooted at the terminal content component.
            val dc = com.intellij.ide.DataManager.getInstance()
                .getDataContext(content.component)

            val controllerClass = Class.forName(
                "org.jetbrains.plugins.terminal.block.prompt.TerminalPromptController"
            )
            val keyField = controllerClass.getDeclaredField("KEY").also { it.isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val key = keyField.get(null) as? com.intellij.openapi.actionSystem.DataKey<Any>
                ?: return null

            val controller = dc.getData(key) ?: return null
            val getCmd = controller.javaClass.getMethod("getCommandText")
            (getCmd.invoke(controller) as? String)?.takeIf { it.isNotBlank() }
        } catch (_: ClassNotFoundException) { null }
          catch (e: Exception) {
              LOG.debug("Block terminal (tool window DC) read failed: ${e.message}")
              null
          }
    }

    private fun writeViaBlockTerminal(event: AnActionEvent, text: String): Boolean = try {
        val controllerClass = Class.forName(
            "org.jetbrains.plugins.terminal.block.prompt.TerminalPromptController"
        )
        val keyField = controllerClass.getDeclaredField("KEY").also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val key = keyField.get(null) as? com.intellij.openapi.actionSystem.DataKey<Any>
            ?: return false

        val controller = event.dataContext.getData(key) ?: return false
        val setCmd = controller.javaClass.getMethod("setCommandText", String::class.java)
        setCmd.invoke(controller, text)
        true
    } catch (_: ClassNotFoundException) { false }
      catch (e: Exception) { LOG.debug("Block terminal write failed: ${e.message}"); false }

    // ── Strategy 3: Classic terminal (ShellTerminalWidget) ───────────────────

    private fun readViaClassicTerminal(event: AnActionEvent): String? = try {
        val widget = findTerminalWidget(event) ?: return null
        // ShellTerminalWidget exposes getTypedShellCommand()
        runCatching { widget.javaClass.getMethod("getTypedShellCommand").invoke(widget) as? String }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { LOG.debug("Classic terminal read failed: ${e.message}"); null }

    private fun writeViaClassicTerminal(event: AnActionEvent, text: String): Boolean = try {
        val widget = findTerminalWidget(event) ?: return false

        // Get TtyConnector and write Ctrl+U (kill line) then the new text.
        val getTty = widget.javaClass.getMethod("getTtyConnector")
        val tty    = getTty.invoke(widget) ?: return false
        val write  = tty.javaClass.getMethod("write", String::class.java)
        write.invoke(tty, "\u0015$text")
        true
    } catch (e: Exception) { LOG.debug("Classic terminal write failed: ${e.message}"); false }

    /**
     * Walk every tool window's component tree looking for a terminal widget.
     * We probe several class names because different IntelliJ versions and
     * plugins (including Claude Code Beta) use different implementations:
     *  - ShellTerminalWidget   — classic terminal plugin
     *  - JediTermWidget        — base class used by some custom terminals
     *  - TerminalExecutionConsole — another wrapping form
     */
    private fun findTerminalWidget(event: AnActionEvent): Any? {
        val project = event.project ?: return null
        val twm     = ToolWindowManager.getInstance(project)

        val candidateClasses = listOf(
            "org.jetbrains.plugins.terminal.ShellTerminalWidget",
            "com.jediterm.terminal.ui.JediTermWidget",
            "com.jediterm.terminal.ui.JediTerminalPanel",
        )

        for (id in twm.toolWindowIds) {
            val tw = twm.getToolWindow(id) ?: continue
            // Only probe tool windows that are already showing — accessing the
            // content component of a hidden window can lazily initialise it and
            // trigger unrelated IDE services (e.g. Backup and Sync storage check).
            if (!tw.isVisible) continue
            val content = tw.contentManager.selectedContent ?: continue
            for (cls in candidateClasses) {
                val widget = findComponentByClassName(content.component, cls)
                if (widget != null) {
                    LOG.debug("Found terminal widget $cls in tool window '$id'")
                    return widget
                }
            }
        }
        return null
    }

    private fun findComponentByClassName(root: Component, className: String): Component? {
        if (root.javaClass.name == className) return root
        if (root is Container) {
            for (child in root.components) {
                val found = findComponentByClassName(child, className)
                if (found != null) return found
            }
        }
        return null
    }

    // ── Strategy 4: Focused text component (e.g. Claude Code Beta input field) ─

    /**
     * When the user presses the shortcut while typing in a non-standard input
     * panel (e.g. the Claude Code Beta dedicated UI), the focused Swing component
     * is a plain JTextComponent.  Read it directly rather than walking tool
     * windows looking for a JediTerm widget.
     *
     * Deliberately excludes IntelliJ editor components so that triggering the
     * shortcut from a code editor doesn't accidentally pre-fill the dialog with
     * source code.
     */
    private fun readViaFocusedTextComponent(): String? {
        return try {
            val focused = java.awt.KeyboardFocusManager
                .getCurrentKeyboardFocusManager().focusOwner ?: return null

            // For plain Swing text components: read unless we're in the main editor area.
            if (focused is javax.swing.text.JTextComponent) {
                if (isInsideMainEditorArea(focused)) return null
                return focused.text.takeIf { it.isNotBlank() }
            }

            // For IntelliJ EditorComponentImpl (used by the block terminal's inline input):
            // only read if the component lives inside a tool window, not the main editor.
            if (focused.javaClass.name.contains("EditorComponent", ignoreCase = true)) {
                if (!isInsideToolWindow(focused)) return null
                return runCatching {
                    focused.javaClass.getMethod("getText").invoke(focused) as? String
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }

            null
        } catch (e: Exception) {
            LOG.debug("Focused text component read failed: ${e.message}")
            null
        }
    }

    /** True when [c] is a descendant of the main editor splitter (not a tool window). */
    private fun isInsideMainEditorArea(c: java.awt.Component): Boolean {
        var p: java.awt.Container? = c.parent
        while (p != null) {
            val name = p.javaClass.name
            if (name.contains("EditorsSplitters") || name.contains("EditorTabs")) return true
            if (name.contains("InternalDecorator") || name.contains("ToolWindowContentUi")) return false
            p = p.parent
        }
        return false
    }

    /** True when [c] is a descendant of a tool window decorator. */
    private fun isInsideToolWindow(c: java.awt.Component): Boolean {
        var p: java.awt.Container? = c.parent
        while (p != null) {
            val name = p.javaClass.name
            if (name.contains("InternalDecorator") || name.contains("ToolWindowContentUi")) return true
            if (name.contains("EditorsSplitters")) return false
            p = p.parent
        }
        return false
    }

    // ── Strategy 5: Clipboard injection ──────────────────────────────────────

    private fun writeViaClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val previous = try { clipboard.getContents(null) } catch (_: Exception) { null }
        try {
            clipboard.setContents(StringSelection(text), null)

            // Ctrl+U to kill the line.
            robot.keyPress(KeyEvent.VK_CONTROL)
            robot.keyPress(KeyEvent.VK_U)
            robot.keyRelease(KeyEvent.VK_U)
            robot.keyRelease(KeyEvent.VK_CONTROL)
            Thread.sleep(60)

            // Paste.
            val modifier = if (IS_MAC) KeyEvent.VK_META else KeyEvent.VK_CONTROL
            robot.keyPress(modifier)
            robot.keyPress(KeyEvent.VK_V)
            robot.keyRelease(KeyEvent.VK_V)
            robot.keyRelease(modifier)

            Thread.sleep(120)
        } catch (e: Exception) {
            LOG.warn("Clipboard injection failed: ${e.message}")
        } finally {
            if (previous != null) try { clipboard.setContents(previous, null) } catch (_: Exception) {}
        }
    }
}
