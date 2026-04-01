# Changelog

All notable changes to the Claude Code Prompt Enhancer plugin are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/). Versions use [Semantic Versioning](https://semver.org/).

## [0.2.0] - 2026-03-31

### Added
- **Claude Code [Beta] plugin** is now a required dependency. The enhancer only works reliably with the dedicated "Claude Code" terminal tab, not a manually started CLI session.
- **Non-modal dialog** — the IDE remains interactive while reviewing the enhanced prompt.
- **Test Connection** button in Settings to verify API key/endpoint without running an enhancement.
- **Elapsed timer** in the dialog shows seconds elapsed during LLM calls.
- **Cmd+Enter / Ctrl+Enter** keyboard shortcut to insert the enhanced prompt from anywhere in the dialog.
- Confirmation dialog before re-enhancing when the enhanced pane has user edits.
- Action added to **Tools menu** for discoverability.
- Design rationale section in README explaining the dialog-based approach.
- `gradlew.bat` for Windows users.
- GitHub Actions CI workflow (build + test on push/PR).
- MIT license.

### Fixed
- **JSON unescape bug**: `extractJsonString` used chained string replacements that corrupted prompts containing `\\n` (literal backslash + n). Replaced with single-pass regex.
- **Subprocess hangs**: added 5-second timeouts to git log and CLI binary resolution subprocesses.
- **System prompt**: hardened against the model answering meta-questions conversationally instead of rewriting them as prompts.

### Changed
- Max context spinner step increased from 100 to 500, upper limit from 20k to 100k.
- Test count increased from 10 to 16 (JSON round-trip edge cases, CLI error paths).

## [0.1.0] - 2026-03-30

Initial release.

### Features
- Keyboard shortcut (Shift+Opt+Cmd+.) to enhance the current terminal prompt
- Two-pane review dialog: rough prompt on top, enhanced prompt (editable) on bottom
- Three API backends: Anthropic API, OpenAI-compatible (Ollama, etc.), and Claude CLI
- Ambient context collection: CLAUDE.md, open files/selection, recent git log
- Configurable system prompt, model, and context toggles in Settings
- API keys stored in OS keychain via JetBrains PasswordSafe
- Multi-version terminal support: Reworked, Block, Classic, clipboard fallback