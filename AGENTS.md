# Agent Instructions

This repository is an sbt plugin for managing Markdown-based LLM skills across sbt 1.x and 2.x builds. Keep changes small and consistent with the existing Scala/sbt plugin style.

## Start Here

- Read [README.md](README.md) for the project overview, supported tasks, requirements, and testing summary.
- Read [USER_GUIDE.md](USER_GUIDE.md) for user-facing configuration examples and troubleshooting details.
- Read [PHASE1_COMPLETE.md](PHASE1_COMPLETE.md) for the current feature set, architecture summary, and roadmap context.

## Build And Test

- Use `sbt compile` for a quick compilation check.
- Use `sbt test` for unit tests under [src/test/scala](src/test/scala).
- Use `sbt scripted` for sbt plugin scripted tests under [src/sbt-test](src/sbt-test).
- Use `sbt HarnessIntegration/test` only when intentionally running CLI-backed harness integration tests. These require authenticated `copilot` and `claude` CLIs and may call remote services.
- For targeted integration checks, prefer `sbt 'HarnessIntegration/testOnly com.github.aborg0.sbt.skills.HarnessCliIntegrationSpec'`.

## Project Shape

- [build.sbt](build.sbt) defines the sbt plugin, cross-build settings, dependencies, scripted setup, publishing, and the `HarnessIntegration` test configuration.
- [src/main/scala/com/github/aborg0/sbt/skills/SbtSkillsPlugin.scala](src/main/scala/com/github/aborg0/sbt/skills/SbtSkillsPlugin.scala) owns the public sbt settings and tasks.
- [src/main/scala/com/github/aborg0/sbt/skills/config](src/main/scala/com/github/aborg0/sbt/skills/config) contains domain models and Circe codecs.
- [src/main/scala/com/github/aborg0/sbt/skills/repo](src/main/scala/com/github/aborg0/sbt/skills/repo) contains Git/JGit fetching and skill discovery logic.
- [src/main/scala/com/github/aborg0/sbt/skills/registry](src/main/scala/com/github/aborg0/sbt/skills/registry) contains registry persistence and harness file generation.
- [src/main/scala/com/github/aborg0/sbt/skills/metrics](src/main/scala/com/github/aborg0/sbt/skills/metrics) contains the append-only metrics collector abstraction and file backend.

## Conventions

- Preserve sbt 1.x and sbt 2.x compatibility. The project cross-builds with Scala 2.12.20 for sbt 1.x and Scala 3.8.4 for sbt 2.x.
- Follow the existing `Try[T]`-based error handling style and include useful context in failures, especially URLs, paths, source ids, and refs.
- Keep task output readable with the existing bracketed log prefixes such as `[SYNC]`, `[SOURCE]`, `[FETCH]`, `[DISCOVER]`, `[REGISTRY]`, `[GENERATE]`, and `[ERROR]`.
- Do not assume harness integration tests are safe as default validation; they depend on local CLI tools, authentication, and external model calls.
- Do not hand-edit generated or cache output under `target/` or `.sbt-skills/` unless the task is specifically about generated artifacts.
- Use [.scalafmt.conf](.scalafmt.conf) for formatting. It is configured for Scala 3 parsing while preserving brace-based syntax.

## Validation Guidance

- For changes to models/codecs/registry/fetching/metrics, run the nearest ScalaTest suite first, then broaden to `sbt test` if needed.
- For changes to sbt task wiring or plugin behavior, run `sbt scripted` after a successful compile or targeted unit test.
- For documentation-only changes, validate links and keep content linked to existing docs instead of copying long sections.