package com.liability.claudeenhancer

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

private val LOG = logger<ContextCollector>()

/**
 * Assembles optional project context to pass alongside the raw prompt.
 *
 * All collectors are individually guarded; if any fails (missing plugin,
 * unreadable file, no git repo) it is silently skipped.
 */
class ContextCollector(private val project: Project) {

    private val settings = EnhancerSettings.getInstance()

    fun collect(): String {
        val s = settings.state
        val parts = mutableListOf<String>()

        if (s.includeClaudeMd)    collectClaudeMd()?.let { parts += "## Project rules (CLAUDE.md)\n$it" }
        if (s.includeOpenFiles)   collectOpenFiles()?.let { parts += it }
        if (s.includeGitContext)  collectGitContext()?.let { parts += "## Recent git history\n$it" }

        return parts
            .joinToString("\n\n")
            .take(s.maxContextChars)
    }

    // ── Collectors ────────────────────────────────────────────────────────────

    private fun collectClaudeMd(): String? = try {
        val base = project.basePath ?: return null
        val vf   = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(base)?.findChild("CLAUDE.md") ?: return null
        String(vf.contentsToByteArray()).takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        LOG.debug("CLAUDE.md collection skipped: ${e.message}")
        null
    }

    private fun collectOpenFiles(): String? = try {
        val fem    = FileEditorManager.getInstance(project)
        val parts  = mutableListOf<String>()

        fem.selectedFiles.firstOrNull()?.let { file ->
            parts += "## Currently open file\n${file.path}"
        }

        fem.selectedTextEditor?.selectionModel?.selectedText
            ?.takeIf { it.isNotBlank() }
            ?.let { sel ->
                parts += "## Selected text\n```\n${sel.take(600)}\n```"
            }

        parts.joinToString("\n\n").takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        LOG.debug("Open-files collection skipped: ${e.message}")
        null
    }

    /**
     * Git log via git4idea.
     * The git4idea plugin is optional; if it is absent this method returns null
     * and the context section is simply omitted.
     */
    private fun collectGitContext(): String? = try {
        GitContextHelper.collect(project)
    } catch (e: ClassNotFoundException) {
        // git4idea not present
        null
    } catch (e: Exception) {
        LOG.debug("Git context collection skipped: ${e.message}")
        null
    }
}

/**
 * Isolated in its own object so the git4idea class references are only loaded
 * when git4idea is on the classpath (the `depends optional` declaration in
 * plugin.xml ensures this).
 *
 * Intentionally calls `git log` via a direct process rather than
 * [git4idea.history.GitHistoryUtils.history], which routes through
 * [git4idea.commands.GitHandlerAuthenticationManager] and triggers the IDE's
 * built-in server auth infrastructure — causing spurious "Backup and Sync
 * History" popups on accounts that don't have that storage configured.
 */
object GitContextHelper {
    fun collect(project: Project): String? {
        val repoManager = git4idea.repo.GitRepositoryManager.getInstance(project)
        val repo        = repoManager.repositories.firstOrNull() ?: return null

        // Branch comes from git4idea's in-memory state — no process needed.
        val branch = repo.currentBranchName ?: "detached HEAD"

        // Recent commits via a plain git subprocess, bypassing auth machinery.
        val log = try {
            val proc = ProcessBuilder(
                "git", "log", "--oneline", "--no-merges", "-5"
            )
                .directory(java.io.File(repo.root.path))
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                ""
            } else output
        } catch (_: Exception) { "" }

        return "Branch: $branch\nRecent commits:\n$log"
    }
}
