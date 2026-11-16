# Contributing to KGBoard

Thank you for your interest in contributing to KGBoard!

## Getting Started

1. Fork the repository
2. Clone your fork: `git clone https://github.com/<your-username>/KGBoard.git`
3. Create a feature branch: `git checkout -b feat/my-feature`
4. Make your changes
5. Build and test: `./gradlew buildPlugin`
6. Commit with a meaningful message (see below)
7. Push and open a Pull Request

## Prerequisites

- JDK 25
- IntelliJ IDEA 2026.3 (build 263.*)
- OpenRGB installed and running (for manual testing)

## Building

```bash
./gradlew buildPlugin
```

The plugin ZIP will be in `build/distributions/`.

To reuse an installed IDEA 2026.3 SDK, pass its installation directory:

```bash
./gradlew test buildPlugin -PlocalPlatformPath=/path/to/idea-2026.3
```

The default build downloads the SDK from JetBrains. Use JDK 25 for either option.

## Running in a sandbox IDE

```bash
./gradlew runIde
```

This launches a sandboxed IntelliJ IDEA instance with the plugin installed.

## Commit Message Format

```
type: short description

Optional longer description.
```

Types: `feat`, `fix`, `refactor`, `docs`, `chore`, `perf`, `test`

## Code Style

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use the `.editorconfig` settings included in the project
- Keep functions small and focused
- Add KDoc comments for public APIs

## Project Structure

| Package | Responsibility |
|---|---|
| `runtime/` | Immutable facts, reducer, policies, deadlines and the single writer |
| `bridge/` | Project-lifetime IDE adapters and UI presentation |
| `client/` | Blocking OpenRGB transport and binary protocol |
| `effect/` | Effect types and pure composition math |
| `settings/` | XML persistence, drafts, validation and scoped previews |

## Changing Runtime Behavior

Write a failing sequence test with `RuntimeHarness` before changing state handling.
Assert emitted frames as well as snapshots. Keep network work on the writer worker;
callbacks only dispatch facts with project/operation/session/revision identifiers.
Subscribe before initial reconciliation, independently of feature flags, and cancel
subscriptions and reads when the owning project closes.

Build/Test terminal layers use the configured duration; cancelled, not-started and
empty test runs must never report success. Analysis is scoped to the selected
document; unknown data must not become a clean result. Only the active project owns
output, and focus dimming applies after composition.

Settings bindings edit a separate draft. Validate the complete draft before replacing
persisted state and dispatching SettingsApplied. Test/Connect use draft values; all
preview cleanup goes through the same runtime writer. Preserve old XML fields and
cover snapshot conversion and serialization round trips when adding settings.

## Verification

Run `./gradlew test buildPlugin` and `./gradlew verifyPlugin`. Tests use JUnit Jupiter 6,
manual time/fake transports and real SDK263 project fixtures. To verify an already
cached SDK without Marketplace access, use `verifyPlugin --offline`.

Hardware QA must record IDE, OpenRGB and device versions and check Connect, Test→Cancel,
two project windows, overlapping operations, focus loss, non-text/no editor, reconnect,
device changes and shutdown. The current migration has no physical-device verification.
Shortcut editing/vcs contexts remain explicit; independent external-system build tasks
outside ProjectTaskManager are not observed.

## Reporting Issues

- Use GitHub Issues
- Include: IDE version, OS, OpenRGB version, plugin version
- Attach relevant logs from `Help > Show Log in Explorer`

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
