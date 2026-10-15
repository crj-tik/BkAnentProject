## Purpose

Provide trusted, versioned business artifact projections with explicit source and dependency information so direct editing and continued AI work can coexist without replacing historical facts or user changes.

## ADDED Requirements

### Requirement: Artifact series SHALL preserve immutable revisions
The API SHALL distinguish seriesId, revisionId, series version and original sourceArtifactId, supporting explicit save with expectedVersion and restoration as a new revision.

#### Scenario: User restores an older draft
- **WHEN** an authorized user restores an existing historical version
- **THEN** a new version is appended and past versions remain unchanged

### Requirement: Projections SHALL expose verified structure and freshness
Supported type/schemaVersion projections SHALL use real source facts, units and references. Condition/source changes SHALL preserve old content with STALE or UNKNOWN freshness rather than silently changing its provenance.

#### Scenario: Listing price changes after comparison
- **WHEN** a new comparison is requested for the same listings
- **THEN** cache selection considers source versions and the result declares its snapshot and units
