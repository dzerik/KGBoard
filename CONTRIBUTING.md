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

- JDK 21+
- IntelliJ IDEA 2024.3+
- OpenRGB installed and running (for manual testing)

## Building

```bash
./gradlew buildPlugin
```

The plugin ZIP will be in `build/distributions/`.

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

```
src/main/kotlin/com/kgboard/rgb/
├── client/     — OpenRGB TCP client and protocol
├── effect/     — Effect engine (static, pulse, flash, progress)
├── bridge/     — IDE event listeners → effects
└── settings/   — Settings persistence and UI
```

## Adding a New Effect

1. Add a new subclass to `RgbEffect.kt`
2. Handle it in `EffectManagerService.applyEffect()` and `restoreCurrentEffect()`
3. Add corresponding settings/colors to `KgBoardSettings`

## Adding a New IDE Event Listener

1. Create a listener class in `bridge/`
2. Register it in `plugin.xml` under `<projectListeners>` or `<applicationListeners>`
3. Use `EffectManagerService` to apply effects in response to events

## Reporting Issues

- Use GitHub Issues
- Include: IDE version, OS, OpenRGB version, plugin version
- Attach relevant logs from `Help > Show Log in Explorer`

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
