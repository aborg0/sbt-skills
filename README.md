# sbt-skills Plugin

A comprehensive sbt 1.x and 2.x plugin for managing LLM skills (Markdown-based documents) from multiple Git repositories with per-source harness overrides, auto-generation of IDE integration files, and extensible metrics tracking.

## 🎯 Overview

The **sbt-skills** plugin solves the problem of organizing, versioning, and distributing AI assistant skills (like Copilot & Claude instructions) across teams and projects. It supports:

- ✅ **Multi-source skill repositories** - Fetch from multiple Git repos
- ✅ **Harness-specific overrides** - Restrict certain skills to Copilot or Claude
- ✅ **Automatic IDE integration** - Generate `.instructions.md` or `SKILLS.md` files
- ✅ **Append-only metrics** - Track skill usage & adoption
- ✅ **Patch and metrics workflows** - Unified-diff overrides and optional Git-backed metrics

## 🚀 Quick Start

### 1. Add to `build.sbt`

```scala
resolvers += "GitHub Packages" at "https://maven.pkg.github.com/aborg0/sbt-skills"
credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  sys.env("GITHUB_ACTOR"),
  sys.env("GITHUB_TOKEN")
)

addSbtPlugin("com.github.aborg0" % "sbt-skills" % "0.1-SNAPSHOT")

skillsSources := Seq(
  SkillSource("mattpocock", "https://github.com/mattpocock/skills.git", "main")
)

skillsHarnesses := Seq("copilot", "claude")
```

### 2. Sync skills

```bash
sbt skillsSync
```

### 3. View results

```bash
sbt skillsList        # Display all registered skills
sbt skillsListSources # Show configured repositories
```

For detailed configuration and examples, see [USER_GUIDE.md](USER_GUIDE.md).

## 📦 Key Features

### Multi-Source Support
Define multiple skill repositories with independent versioning:

```scala
skillsSources := Seq(
  SkillSource("public", "https://github.com/org/public-skills.git", "main"),
  SkillSource("internal", "https://github.com/org/private-skills.git", "v1.0")
)
```

### Per-Source Harness Overrides
Apply harness restrictions at the source level:

```scala
skillsSourceHarnessOverrides := Map(
  "internal" -> Seq("claude"),  // Internal skills → Claude only
  "public"   -> Seq("copilot")  // Public skills → Copilot only
)
```

### Auto-Generated IDE Integration Files
Automatically generate or update `.instructions.md` with all available skills:

```scala
skillsAutoGenerate := true
skillsHarnessMode := "instructions-file"  // or "registry-file" or "both"
```

### Extensible Metrics Foundation
Track skill sync events in an append-only file or commit them to a Git branch. Local Git commits are
enabled with the `git` backend; pushing is separately opt-in.

```scala
skillsMetricsBackend := "file"  // or "git" or "none"
skillsMetricsFile := baseDirectory.value / ".sbt-skills" / "metrics.jsonl"
skillsMetricsGitBranch := "metrics"
skillsMetricsGitRemote := "origin"
skillsMetricsGitPush := false
```

For a user-specific branch, use a stable identity from the CI or developer environment:

```scala
skillsMetricsGitBranch := s"user/${sys.env.getOrElse("GITHUB_ACTOR", "local")}"
skillsMetricsGitPush := true
```

The metrics file must be inside an existing local checkout of the target repository. When pushing
is enabled, the collector uses `GITHUB_TOKEN` as an environment-only credential and never writes it
to the build or metrics files.

Set `skillsMetricsGitRepository` to have the plugin clone and manage the metrics repository
automatically. Before a push, it fetches and rebases onto the remote branch; JSONL conflicts retain
remote events first and append new local events at the end.

Feedback uses a separate versioned schema:

```bash
sbt 'skillsFeedback internal:security/review 5 "The review guidance was useful"'
```

## 🛠️ Supported Harnesses

Current implementations:
- **copilot** - GitHub Copilot IDE integration
- **claude** - Anthropic Claude AI assistant

Extensible for future harnesses (GPT, Gemini, etc.)

## 📚 Main Tasks

| Task | Purpose |
|------|---------|
| `skillsSync` | Fetch repos, register skills, generate IDE files |
| `skillsList` | Display registered skills by source |
| `skillsListSources` | Show configured repositories |

## 📖 Documentation

- **[USER_GUIDE.md](USER_GUIDE.md)** - Complete user guide with configuration reference, examples, and troubleshooting
- **Configuration** - See `build.sbt` for all settings
- **API** - Well-documented source code in `src/main/scala/com/github/aborg0/sbt/skills/`

## 🧪 Testing

Run tests:
```bash
sbt test       # 14/14 unit tests passing ✓
sbt scripted   # 2/2 scripted tests passing ✓
sbt compile    # Full compilation (0 errors) ✓
```

Authenticated harness CLI tests are opt-in and are not included in `test` or `scripted`:

```bash
sbt 'HarnessIntegration/testOnly com.github.aborg0.sbt.skills.CopilotCliIntegrationSpec'
sbt 'HarnessIntegration/testOnly com.github.aborg0.sbt.skills.ClaudeCliIntegrationSpec'
```

These tests assume the selected `copilot` or `claude` executable is installed and authenticated. Running
`sbt HarnessIntegration/test` requires both CLIs. They invoke remote models and may consume credits. The
Copilot test reads the generated file from its temporary workspace; the Claude test supplies it as system
instructions.

Test coverage:
- ✅ JSON serialization (File, Instant, SkillSource, SkillReference)
- ✅ Registry operations (load, save, add skills, filter by harness)
- ✅ Plugin task availability

## 🔧 Requirements

- **sbt** 1.13+ or 2.0+ (tested with 1.13.0 and 2.0.8)
- **Scala** 2.12
- **Java** 11+

## 📦 Dependencies

- **JGit** 6.7.0 - Git operations
- **Circe** 0.14.6 - JSON serialization  
- **sbt logging** - Integrated with the consuming build's logger
- **ScalaTest** 3.2.17 - Testing framework

## 📈 Roadmap

### Phase 1 (Current MVP) ✅
- ✅ Multi-source support with per-source overrides
- ✅ IDE integration file generation
- ✅ File-based metrics tracking
- ✅ Comprehensive error handling & diagnostics

### Phase 2 ✅
- Unified-diff patch management through `skillsPatchesDir`
- `skillsUpdate` task for source-ref changes
- Per-skill version overrides
- `skillsAdd`, `skillsRemove`, and `skillsInfo` tasks

### Phase 3 ✅ Foundation
- Git-based metrics backend with local commits
- Sync, add, and remove event tracking
- Survey and dashboard consumers can build on the append-only event stream

## 🤝 Contributing

Contributions welcome! Areas for enhancement:
- Additional harness types
- Patch management (Phase 2)
- Advanced metrics (Phase 3)
- Test coverage improvements

## 📄 License

Apache 2.0

---

**Getting started?** Read [USER_GUIDE.md](USER_GUIDE.md) for detailed setup instructions and configuration examples.
