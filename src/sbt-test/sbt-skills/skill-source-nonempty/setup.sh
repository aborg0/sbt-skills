#!/usr/bin/env bash
set -e

rm -rf upstream-repo
mkdir -p upstream-repo/skills/engineering/code-review
cd upstream-repo

git init
git checkout -b main
git config user.email "test@test.com"
git config user.name "Test"
git config commit.gpgsign false

cat > skills/engineering/code-review/SKILL.md << 'EOF'
---
harnesses: copilot,claude
---

# Code Review

Guidance for performing thorough code reviews.
EOF

git add .
git commit -m "initial commit"
