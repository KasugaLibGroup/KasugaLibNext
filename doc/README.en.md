# KasugaLibNext Documentation

This site is the development documentation for KasugaLibNext and its downstream mods, aimed at mod authors who want to integrate rendering, modelling, and data-driven capabilities.

## Site Structure

| Section | Intended reader | Content |
| --- | --- | --- |
| [getting-started/](getting-started/) | First-time integration | Setting up a project from scratch and getting the first example running |
| [guides/](guides/) | Arriving with a concrete task | Getting-started tutorials, example recipes, and verification steps |
| [reference/](reference/) | Looking up signatures | APIs, configuration schema, commands, and failure behavior |
| [explanation/](explanation/) | Wanting to understand the mechanics | Module boundaries, data flow, algorithms, and resource ownership |
| [meta/](meta/) | Project and versions | Compatibility matrix, migration guides, licenses, contributor documentation, and verification records |

## Documentation Maintenance

When adding a callable API, complete its package name, signature, units, default values, error conditions, and lifecycle in `reference/`, and link to the corresponding explanation;
when changing an API, update the call examples and configuration tables in step; when migrating or renaming, update the index, the root README, relative links, and anchors.
Explanation docs use type names, mathematical expressions, and diagrams to explain the mechanics; method references, configuration tables, and operating steps belong in `reference/`.

The full conventions are in the "Documentation Maintenance" section of [reference/README.md](reference/README.md).
