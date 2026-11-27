# KasugaLibNext Documentation

This site is the development documentation for KasugaLibNext and its downstream mods, aimed at mod authors who want to integrate core registration, dependency injection, scripting, rendering, modelling, data-driven content, and Create integration.

## Site Structure

| Section | Intended reader | Content |
| --- | --- | --- |
| [getting-started/](getting-started/) | First-time integration | Dependencies and runtime environment, minimal example project, and installation/deployment |
| [guides/](guides/) | Arriving with a concrete task | Data-driven walkthroughs, content and Proxy integration, world display and lights, model collision, recipes, and verification |
| [reference/](reference/) | Looking up signatures | APIs, configuration schemas, commands, and failure behavior across Core/Inject/Utils, scripting, Gradle/packaging, data-driven, models, rendering, and Create |
| [explanation/](explanation/) | Wanting to understand the mechanics | Threading model, data-driven v2, Proxy, animation scope/targets, render pipelines, physics, and module boundaries |
| [meta/](meta/) | Project and versions | Compatibility matrix, migration guides, changelog, licenses, contributor docs, known limitations, consumers, glossary, and benchmark records |

## Documentation Maintenance

When adding a callable API, complete its package name, signature, units, default values, error conditions, and lifecycle in `reference/`, and link to the corresponding explanation;
when changing an API, update the call examples and configuration tables in step; when migrating or renaming, update the index, the root README, relative links, and anchors.
Explanation docs use type names, mathematical expressions, and diagrams to explain the mechanics; method references, configuration tables, and operating steps belong in `reference/`.

The full conventions are in the "Documentation Maintenance" section of [reference/README.md](reference/README.md).
