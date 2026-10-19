# Thin wrappers: every recipe is one command, and everything works without `just`.
# EDP_* settings come from .env (see .env.example) and reach sbt's forked test JVM too.

set dotenv-load := true

pkg := "com.thinkmorestupidless.experimentproposals"
checker := 'uvx --from "git+https://github.com/thinkmorestupidless/speckit-bdd@v0.2.0#subdirectory=checker" speckit-bdd'

# Enable the pre-commit hook once per clone.
hooks:
    git config core.hooksPath .githooks

# Postgres with ankka's schema.
up:
    sbt schema && docker compose up -d

down:
    docker compose down -v

# The service on :9000.
run:
    sbt run

# Entity, rule, renderer, parser and keyed-view tests; no Docker.
test-unit:
    sbt "testOnly {{pkg}}.unit.*"

# Workflows, agents with a scripted model, routes and restarts; Docker.
test-integration:
    sbt "testOnly {{pkg}}.integration.*"

# The suite written from the spec, not the prompts.
independent:
    sbt "testOnly {{pkg}}.independent.*"

# Fault injection (SC-005); Docker.
faults:
    sbt "testOnly {{pkg}}.faults.*"

# The living features through HTTP, against the recorded model replies.
features-replay:
    EDP_TIER=replay sbt "testOnly {{pkg}}.features.FeatureSuite"

# The same features against the live model; writes evals/reports/drift.json.
features-live:
    EDP_TIER=live sbt "testOnly {{pkg}}.features.FeatureSuite"

# The live tier, recording a cassette for every model call it makes.
features-record:
    EDP_TIER=live EDP_RECORD=1 sbt "testOnly {{pkg}}.features.FeatureSuite"

# The labelled evaluation run through the service (Docker); writes evals/reports/<timestamp>.json and .md.
eval:
    sbt "testOnly {{pkg}}.evaluation.EvaluationSuite"

# The part of the evaluation that needs no service: the dataset's validity and the passages' gate.
eval-offline:
    sbt "runMain {{pkg}}.evaluation.evalRun offline"

eval-compare report:
    sbt "runMain {{pkg}}.evaluation.evalRun compare {{report}}"

# Author a cassette for a stage and case from a reply file, checking its quotations.
cassette stage case reply:
    sbt "runMain {{pkg}}.replay.CassetteAuthor {{stage}} {{case}} {{reply}}"

# The living features, the glossary and the specs that name them.
check-bdd:
    {{checker}} check --root . --glossary GLOSSARY.md --features features --specs specs

# SC-013: three safeguards disabled in a disposable copy, each must fail an independent test.
mutants:
    scripts/mutants.sh

fmt:
    sbt scalafmtAll scalafmtSbt

image:
    sbt Docker/publishLocal

# The pull-request tier.
pr: check-bdd test-unit test-integration independent features-replay

# Check every page, then build the documentation site into target/docs-site.
docs:
    uv run --project tools/docs docs build

# Refresh the code samples the pages include from their sources.
docs-sync:
    uv run --project tools/docs docs sync

# The documentation site with live reload, while writing.
docs-serve:
    uv run --project tools/docs docs serve

# Re-render the documentation diagrams from their Mermaid sources.
docs-diagrams:
    for f in docs/assets/diagrams/*.mmd; do npx -y @mermaid-js/mermaid-cli@11.4.2 -i "$f" -o "${f%.mmd}.svg" -b white; done
