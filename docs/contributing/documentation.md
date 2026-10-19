---
title: Writing documentation
description: How this documentation is built with ankka's docs tool, where a page goes, the rules every page follows, and how code samples and diagrams are kept in step with the code.
kind: contributing
related: [reference/limitations.md]
---

# Writing documentation

The documentation is one tree of plain Markdown under `docs/`, built into a site by ankka's docs tool,
which also checks every page. The tool is taken as a git dependency in `tools/docs/pyproject.toml`; the
site's navigation is in `mkdocs.yml`.

## The build

```bash
just docs          # check every page, then build the site into target/docs-site
just docs-sync     # refresh included code samples from their sources
just docs-serve    # the site with live reload, while writing
just docs-diagrams # re-render the diagrams from their Mermaid sources
```

The site is published to GitHub Pages from `main` by `.github/workflows/docs.yml`, which also runs the
build as a check on every pull request that touches the documentation or the code it includes.

## Where a page goes

| Kind | Directory | The reader wants to |
|---|---|---|
| `tutorial` | `get-started/` | be walked from nothing to something working |
| `concept` | `concepts/` | understand how something works and why |
| `guide` | `build/`, `deploy/` | get one task done |
| `reference` | `reference/` | look one fact up |
| `contributing` | `contributing/` | change the documentation or the project |

Every page starts with frontmatter giving `title`, `description` and `kind`, and optionally `related`. A
new page goes in the `nav` in `mkdocs.yml`, or the check fails.

## The rules

A page stands alone: a reader, or a model, may receive it with no navigation around it. So it does not
say "above" or "below", it links instead. It describes the system as it is, not the history of how it
was built: no feature or task numbers and no paths into the project's specification files. The check
enforces both.

## Code samples

A sample is never pasted. It is a fenced block preceded by an include comment naming a region of a
source file, and the region is marked in the source with `docs:start <name>` and `docs:end <name>`
comments. `just docs-sync` copies the region into the page; `just docs` fails when a copy has drifted, so
the sample a reader sees is the code that compiles and is tested.

## Diagrams

Diagrams are Mermaid sources under `docs/assets/diagrams/*.mmd`, rendered to SVG by `just
docs-diagrams` and committed. The image's alt text describes the whole diagram in prose, because
everywhere except the site the picture is gone and the alt text is all a reader gets. The README shows
the same two diagrams as Mermaid, which GitHub renders.
