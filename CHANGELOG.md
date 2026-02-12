# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.2] - 2026-02-12

### Changed
- Updated README: added focus/lifecycle documentation, new settings, updated architecture diagram, file tree, code examples (Kotlin UI DSL v2)

## [0.1.1] - 2026-02-12

### Added
- LICENSE (Apache 2.0)
- CONTRIBUTORS.md
- CONTRIBUTING.md with build/dev guide and contribution workflow
- CODE_OF_CONDUCT.md (Contributor Covenant 2.1)
- SECURITY.md with vulnerability reporting policy
- .editorconfig for consistent code formatting

## [0.1.0] - 2026-02-12

### Added
- OpenRGB TCP client with full binary protocol implementation
- Effect engine: static, pulse, flash, progress effects with priority system
- Build event listener: success (green flash), failure (red static), in-progress (yellow pulse)
- Execution listener: run (green static), debug (purple pulse), abnormal exit (orange flash)
- Test listener: running (blue pulse), passed (green flash), failed (red static)
- Focus handling: dim keyboard on IDE focus loss, restore on focus gain
- Lifecycle handling: reset LEDs to black on IDE exit
- Project close handling: clear effects and return to idle
- Settings UI with native color pickers (Kotlin UI DSL v2)
- Status bar widget with connection indicator and toggle
- Auto-connect on startup, auto-reconnect on connection loss
- Persistent settings storage in `kgboard.xml`
- Comprehensive README with architecture docs and setup guide
