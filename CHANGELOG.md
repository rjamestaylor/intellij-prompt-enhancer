# Changelog

## 0.1.0

Initial release.

### Features
- Keyboard shortcut (Shift+Opt+Cmd+.) to enhance the current terminal prompt
- Two-pane review dialog: rough prompt on top, enhanced prompt (editable) on bottom
- Three API backends: Anthropic API, OpenAI-compatible (Ollama, etc.), and Claude CLI
- Ambient context collection: CLAUDE.md, open files/selection, recent git log
- Configurable system prompt, model, and context toggles in Settings

### Fixes
- Fixed potential deadlock in CLI backend when stderr pipe buffer fills
- Clipboard-injection fallback now runs off EDT to prevent UI freeze
- Cached CLI binary resolution to avoid repeated filesystem probes per invocation
- Password field CharArray zeroed after use in settings UI
- Changed `EnhancerState` from `data class` to plain `class` (mutable persistent state should not generate `copy()`/`equals()`)

### Upgrade Notes
- **System prompt update**: The default system prompt has been significantly improved to prevent the model from refusing or redirecting non-coding inputs. If you installed a pre-release build and the enhancer refuses to rewrite certain prompts, go to **Settings -> Tools -> Claude Prompt Enhancer** and click **Reset to default** next to the system prompt field.