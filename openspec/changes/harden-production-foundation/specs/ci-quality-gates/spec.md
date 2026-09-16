## Purpose

This capability establishes repeatable repository gates so compile, test, packaging, specification, and credential-safety regressions are detected before changes are merged.

## ADDED Requirements

### Requirement: Pull requests SHALL run the repository quality gates

Continuous integration SHALL run on pull requests and pushes and SHALL execute the supported Java build, automated tests, deployable-package verification, strict OpenSpec validation, and a scan for usable credentials in checked-in configuration.

#### Scenario: Pull request contains a regression

- **WHEN** compilation, tests, packaging, strict specification validation, or credential scanning fails
- **THEN** the CI check SHALL fail
- **AND** the change SHALL not be considered merge-ready

#### Scenario: Pull request passes all gates

- **WHEN** all required checks complete successfully
- **THEN** CI SHALL publish concise logs or reports sufficient to diagnose a later failure
- **AND** reports SHALL not include secret values

### Requirement: CI SHALL use reproducible repository commands

Quality gates SHALL use commands and configuration checked into the repository or documented as required inputs. CI SHALL not depend on a developer's local Maven settings, untracked credentials, or unavailable external infrastructure for the baseline verification path.

#### Scenario: Clean runner executes baseline verification

- **WHEN** CI runs on a clean Java 17 runner without Nacos, MySQL, or provider credentials
- **THEN** the baseline compile, test, package, and specification checks SHALL be executable
- **AND** infrastructure-dependent integration checks SHALL be clearly separated or skipped by an explicit profile
