# Recorded adapter responses

Real responses, recorded on 2026-10-17 from the public APIs, served by a loopback stub in the unit
tests so no test reaches the network. Nothing here is edited.

| File | Request |
|---|---|
| `europepmc/search-PMC12729497.json` | `search?query=PMCID:PMC12729497&format=json&resultType=core` |
| `europepmc/PMC12729497.fullTextXML` | `PMC12729497/fullTextXML` |
| `europepmc/search-PMC12999981.json` | a retracted publication, `resultType=core` |
| `europepmc/search-MED-39491705.json` | an article with no full text in Europe PMC, `resultType=core` |
| `pubmed/esearch.json` | `esearch.fcgi?db=pubmed&term=goose fatty liver hepatocytes lipid&retmode=json&retmax=3` |
| `pubmed/efetch-39491705.xml` | `efetch.fcgi?db=pubmed&id=39491705&retmode=xml` |
| `pubmed/efetch-26098879.xml` | a retracted publication, `efetch.fcgi?...&retmode=xml` |
| `crossref/work-10.3390-ani15243603.json` | `works/10.3390/ani15243603` |
| `crossref/updates-wakefield.json` | `works?filter=updates:10.1016/S0140-6736(97)11096-0&rows=3` |
| `crossref/search.json` | `works?query=goose fatty liver hepatocytes lipid&rows=3&select=...` |
| `pubmed/esummary.json` | `esummary.fcgi?db=pubmed&id=42685486,42341503,41751030&retmode=json` |
| `crossref/work-10.1016-S0140-6736-97-11096-0.json` | `works/10.1016/S0140-6736(97)11096-0` (a retracted article) |
