# sbt-skills Plugin: User Guide

## Overview

The **sbt-skills** plugin helps you fetch, manage, and integrate LLM skills (markdown SKILL.md files) from multiple external repositories into your sbt project. It supports multiple skill sources with per-source harness overrides (e.g., use certain skills only with Copilot or Claude) and automatically generates IDE integration files.

**Key Features:**
- ✅ Fetch skills from multiple Git repositories
- ✅ Support for branch/tag/commit-specific references
- ✅ Per-source harness restrictions (e.g., internal skills → Claude only)
- ✅ Auto-generate `.instructions.md` or `SKILLS.md` for IDE integration
- ✅ Append-only metrics tracking
- ✅ Extensible for future patch management and updates

---

## Installation

Add to your `build.sbt`:

```scala
addSbtPlugin("com.github.aborg0" % "sbt-skills" % "0.1.0")
```

---

## Quick Start (30 seconds)

### 1. Configure sources

Add to your `build.sbt`:

```scala
lazy val root = (project in file("."))
  .settings(
    // Define skill repositories
    skillsSources := Seq(
      SkillSource("mattpocock", "https://github.com/mattpocock/skills.git", "main"),
      SkillSource("internal", "https://github.com/yourorg/internal-skills.git", "v1.0")
    ),
    
    // Select which skills to use (format: sourceId:category/skillname)
    skillsToAdd := Seq(
      "mattpocock:engineering/code-review",
      "mattpocock:productivity/handoff",
      "internal:scala/testing-patterns"
    ),
    
    // Specify harness(es)
    skillsHarnesses := Seq("copilot", "claude")
  )
```

### 2. Sync skills

```bash
sbt skillsSync
```

This will:
- Clone/update all configured repositories
- Discover all SKILL.md files
- Create `.sbt-skills/registry.json`
- Generate `.instructions.md` for your IDE

### 3. Check your work

```bash
sbt skillsList        # See all registered skills
sbt skillsListSources # See all configured sources
```

---

## Configuration Reference

### Required Settings

#### `skillsSources`
Define the external repositories containing skills.

```scala
skillsSources := Seq(
  SkillSource(
    id = "mattpocock",
    url = "https://github.com/mattpocock/skills.git",
    ref = "main"  // branch, tag, or commit hash
  ),
  SkillSource(
    id = "internal",
    url = "https://github.com/yourorg/internal-skills.git",
    ref = "v1.2.0"
  )
)
```

#### `skillsToAdd`
Which skills to include. Use format: `sourceId:category/skillname`

```scala
skillsToAdd := Seq(
  "mattpocock:engineering/code-review",
  "mattpocock:productivity/handoff",
  "internal:scala/testing-patterns"
)
```

#### `skillsHarnesses`
Global default harnesses. Applies to all sources unless overridden.
Each skill is enabled only for configured harnesses that it also declares in its `SKILL.md` metadata.

```scala
skillsHarnesses := Seq("copilot", "claude")
```

### Optional Settings

#### `skillsSourceHarnessOverrides`
Restrict certain skills to specific harnesses.

```scala
skillsSourceHarnessOverrides := Map(
  "internal"  -> Seq("claude"),   // Internal skills ONLY for Claude
  "community" -> Seq("copilot")   // Community skills ONLY for Copilot
)
// mattpocock not listed → uses global default (copilot + claude)
```

Overrides replace the global list for that source, then are limited to the harnesses supported by each skill.

#### `skillsSourceExclude`
Temporarily disable sources without removing config.

```scala
skillsSourceExclude := Seq(
  "community"  // This source won't be synced
)
```

#### `skillsHarnessMode`
Output format for IDE integration.

```scala
skillsHarnessMode := "instructions-file"  // .instructions.md (concatenated skills)
// OR
skillsHarnessMode := "registry-file"      // SKILLS.md (machine-readable table)
// OR
skillsHarnessMode := "both"               // Both files
```

#### `skillsOutputDir`
Where to write generated harness files (default: project root).

```scala
skillsOutputDir := baseDirectory.value
```

#### `skillsAutoGenerate`
Auto-generate harness files on sync (default: true).

```scala
skillsAutoGenerate := true
```

#### `skillsMetricsBackend`
Metrics storage (currently only "file" supported; "git" planned for Phase 3).

```scala
skillsMetricsBackend := "file"
```

#### `skillsMetricsFile`
Where metrics are stored (default: `.sbt-skills/metrics.jsonl`).

```scala
skillsMetricsFile := baseDirectory.value / ".sbt-skills" / "metrics.jsonl"
```

---

## Usage

### `sbt skillsSync`

Fetch all configured repositories and register skills.

**Output:**
```
══════════════════════════════════════════════════════════
[SYNC] Starting skills synchronization...
══════════════════════════════════════════════════════════
[INFO] Configured sources: mattpocock, internal
[INFO] Loaded existing registry with 2 skill(s)

[SOURCE] Syncing 'mattpocock'...
[FETCH] ✓ Repository ready at .sbt-skills/sources/mattpocock
[DISCOVER] Found 5 skill(s)
  ✓ engineering/code-review (harnesses: copilot, claude)
  ✓ productivity/handoff (harnesses: copilot, claude)
  ...

[SOURCE] Syncing 'internal'...
[FETCH] ✓ Repository ready at .sbt-skills/sources/internal
[DISCOVER] Found 2 skill(s)
  ✓ scala/testing-patterns (harnesses: claude)
  ...

[REGISTRY] Saved registry to .sbt-skills/registry.json
[REGISTRY] Total skills: 7

[GENERATE] Generating harness output (mode: instructions-file)...
[GENERATE] ✓ Harness files generated successfully

══════════════════════════════════════════════════════════
[SUMMARY] ✓ Sync completed successfully! 2 source(s) synced
══════════════════════════════════════════════════════════
```

### `sbt skillsList`

Show all registered skills.

**Output:**
```
══════════════════════════════════════════════════════════
[LIST] Skills Registry
══════════════════════════════════════════════════════════
[INFO] Total skills: 7

[SOURCE] mattpocock
  ├─ engineering/code-review
  │  Harnesses: claude, copilot
  │  Version: 3a5f8b9

  ├─ productivity/handoff
  │  Harnesses: claude, copilot
  │  Version: 3a5f8b9

[SOURCE] internal [Harnesses: claude]
  ├─ scala/testing-patterns
  │  Harnesses: claude
  │  Version: 1f2e3d4

══════════════════════════════════════════════════════════
```

### `sbt skillsListSources`

Show configured skill sources and sync status.

**Output:**
```
══════════════════════════════════════════════════════════
[SOURCES] Configured Skill Repositories
══════════════════════════════════════════════════════════
[INFO] Total sources: 2

[ID] mattpocock
  URL: https://github.com/mattpocock/skills.git
  Ref: main
  Cache: /path/to/project/.sbt-skills/sources/mattpocock

[ID] internal [Harnesses: claude]
  URL: https://github.com/yourorg/internal-skills.git
  Ref: v1.0
  Cache: /path/to/project/.sbt-skills/sources/internal

══════════════════════════════════════════════════════════
```

---

## Common Use Cases

### Use Case 1: Public + Internal Skills

Mix public skills (available to both Copilot and Claude) with internal proprietary skills (Claude only):

```scala
skillsSources := Seq(
  SkillSource("mattpocock", "https://github.com/mattpocock/skills.git", "main"),
  SkillSource("internal", "https://github.com/yourorg/internal-skills.git", "main")
)

skillsToAdd := Seq(
  "mattpocock:engineering/code-review",     // Public
  "mattpocock:productivity/handoff",        // Public
  "internal:scala/company-patterns"         // Private
)

skillsHarnesses := Seq("copilot", "claude")

// Restrict internal skills to Claude only
skillsSourceHarnessOverrides := Map(
  "internal" -> Seq("claude")
)
```

### Use Case 2: Development vs. Production

Use different skill sets for dev and production:

```scala
// In dev/build.sbt
skillsSources := Seq(
  SkillSource("mattpocock", "...", "main"),
  SkillSource("experimental", "...", "develop")  // Beta/experimental
)

// In prod/build.sbt (or switch via exclude)
skillsSourceExclude := Seq("experimental")  // Skip experimental in prod
```

### Use Case 3: Minimal Production Setup

Only critical skills with no metrics:

```scala
skillsSources := Seq(
  SkillSource("internal", "...", "v1.0")  // Pinned to stable tag
)

skillsToAdd := Seq(
  "internal:critical/security-review",
  "internal:critical/performance-audit"
)

skillsHarnesses := Seq("claude")  // Claude only (no Copilot)

skillsMetricsBackend := "none"  // Disable metrics
```

---

## File Structure

After running `sbt skillsSync`:

```
project-root/
├── .sbt-skills/
│   ├── sources/
│   │   ├── mattpocock/              # Cloned repo
│   │   │   └── skills/
│   │   │       ├── engineering/
│   │   │       │   └── code-review/
│   │   │       │       └── SKILL.md
│   │   │       └── ...
│   │   ├── internal/                # Cloned repo
│   │   │   └── ...
│   │   ├── registry.json            # Master registry (all skills)
│   │   └── metrics.jsonl            # Metrics log (append-only)
│   └── patches/                     # (Phase 2: customizations)
│
├── .instructions.md                 # Generated: concatenated skills
└── SKILLS.md                        # Generated: skill registry table (if mode="registry-file")
```

---

## Registry Format (`.sbt-skills/registry.json`)

```json
{
  "sources": [
    {
      "id": "mattpocock",
      "url": "https://github.com/mattpocock/skills.git",
      "ref": "main",
      "harnessOverride": null,
      "lastSynced": "2026-08-30T12:00:00Z"
    },
    {
      "id": "internal",
      "url": "https://github.com/yourorg/internal-skills.git",
      "ref": "v1.0",
      "harnessOverride": ["claude"],
      "lastSynced": "2026-08-30T12:05:00Z"
    }
  ],
  "skills": [
    {
      "id": "code-review",
      "sourceId": "mattpocock",
      "category": "engineering",
      "path": "skills/engineering/code-review/SKILL.md",
      "version": "3a5f8b9",
      "harnessesInRepo": ["copilot", "claude"],
      "effectiveHarnesses": ["copilot", "claude"],
      "lastFetched": "2026-08-30T12:00:00Z",
      "customized": false
    }
  ]
}
```

---

## Troubleshooting

### Problem: `[ERROR] No skill sources configured`

**Solution:** Add `skillsSources` to your `build.sbt`.

```scala
skillsSources := Seq(
  SkillSource("myrepo", "https://github.com/user/skills.git", "main")
)
```

### Problem: Skills not found after sync

**Solution:** Verify the repository structure matches expected format:
- Expected: `skills/category/skillname/SKILL.md`
- Check: Repository actually has a `skills/` directory

```bash
# Verify repo structure
find .sbt-skills/sources/myrepo -name "SKILL.md"
```

### Problem: `[ERROR] Failed to fetch repository`

**Possible causes:**
1. Network issues (check internet connectivity)
2. Invalid Git URL (verify URL is correct and accessible)
3. SSH key issues (if using SSH URLs)
4. Permissions (check GitHub access rights)

**Solution:**
```bash
# Test Git clone manually
git clone https://github.com/yourorg/skills.git /tmp/test-skills
```

### Problem: Skills not appearing in generated `.instructions.md`

**Solution:** Check that `skillsAutoGenerate` is enabled and harnesses match:
1. Verify `skillsAutoGenerate := true` (default)
2. Check `skillsHarnessMode` is set correctly
3. Ensure skill's `effectiveHarnesses` includes your configured harness

Run `sbt skillsList` to see which skills are registered and their harnesses.

### Problem: Seeing lots of debug messages

**Solution:** Debug logging is on by default. To suppress:
- Use `sbt -warn` flag
- Run with sbt's debug logging enabled for additional diagnostics

---

## Tips & Best Practices

1. **Pin to stable refs:** Use tags (e.g., `v1.0.0`) instead of `main` for production:
   ```scala
   SkillSource("internal", "...", "v1.0.0")  // Stable
   ```

2. **Organize by category:** Group related skills in the same category:
   ```scala
   skillsToAdd := Seq(
     "myrepo:ml/feature-engineering",
     "myrepo:ml/hyperparameter-tuning",
     "myrepo:ml/model-evaluation"
   )
   ```

3. **Use source overrides for security:** Keep sensitive skills to specific harnesses:
   ```scala
   skillsSourceHarnessOverrides := Map(
     "security" -> Seq("claude")  // Security reviews via Claude only
   )
   ```

4. **Monitor metrics:** Check `.sbt-skills/metrics.jsonl` for usage insights (Phase 3).

5. **Review generated files:** Check `.instructions.md` or `SKILLS.md` to ensure skills integrated correctly before committing.

---

## Version History

- **0.1.0** (MVP / Phase 1)
  - Multi-source support
  - Per-source harness overrides
  - `.instructions.md` + `SKILLS.md` generation
  - File-based metrics (foundation for Phase 3)
  - Comprehensive error messages & diagnostics

- **0.2.0** (Planned / Phase 2)
  - Patch management (`skillsPatchesDir`)
  - Update detection & conflict resolution
  - `skillsUpdate` task

- **0.3.0** (Planned / Phase 3)
  - Git-based metrics backend
  - User satisfaction surveys
  - Metrics aggregation dashboard

---

## Support

For issues, feature requests, or questions:
- GitHub Issues: [aborg0/sbt-skills](https://github.com/aborg0/sbt-skills/issues)
- Documentation: [sbt-skills README](https://github.com/aborg0/sbt-skills)

