# Legacy ChainCash prototype retirement

## Status

Basis v1 (`basis.es` and `basis-token.es`), the original `onchain`
reserve/note/receipt family and the experimental `layer2-old` family are
retired from the production build. Their exact contract sources remain
test-only historical fixtures. The old Basis demos and operational walkthroughs
are removed from the working tree and recoverable at commit
`78475e30362571acf56e4e38276a9d6c0a84ce0c`. Production code must not compile
the retired contracts, derive or print their addresses, or build transactions
that activate them.

This is a quarantine boundary, not an in-place contract repair. An immutable
box already protected by one of the old ErgoTrees would still be governed by
that old tree. The repository does not contain a complete deployment inventory,
so this change makes no claim that no such boxes exist.

## Production boundary

- `Constants` exposes only `Basis v2` and `Basis-token v2` through
  `publishedContracts`.
- The production printer derives its output exclusively from that registry.
- The v1 deployer/note creator, participant-secret loader, old reserve/note
  construction helpers and legacy scan-rule printer are absent from the
  production classpath and main JAR.
- The old ErgoScript files live under `src/test/resources` and are loaded only
  by historical tests. Removed Scala helpers remain recoverable from the pinned
  Git history, but are not copied into a runtime or resource artifact.
- `verifyMainJarRetirement` opens the actual `Compile / packageBin` JAR and
  rejects every retired class/resource prefix before the full test task runs.
- Read-only prototype tracking remains available for inventory work, but it is
  not a supported admission, construction, settlement, or migration path.

## Validation matrix

| Invariant | Producer / enforcement | Consumer | Failure if relaxed | Regression |
| --- | --- | --- | --- | --- |
| No v1/legacy source, tree, address or parameter getter is exported | `Constants` production API | Address and deployment tooling | A caller can accidentally publish an unreviewed old P2S | Reflection rejects every retired getter |
| Address output is allowlisted | `publishedContracts` | `Printer` | A historical address can be presented as active | Captured printer output equals the two v2 registry entries exactly |
| Direct old-generation activation helpers are not compiled | SBT source layout and package task | Downstream applications | A v1 builder, secret loader or legacy scan rule remains callable | Classpath negatives plus real-JAR prefix/resource inspection |
| Historical regression inputs remain exact across checkout line endings | Canonical UTF-8 LF SHA-256 manifest | `BasisSpec`, `BasisTokenSpec`, `ChainCashSpec` | Tests silently exercise changed source while retaining the old name, or fail only because Git checked out CRLF | All nine contract fixture digests are checked before use; LF and CRLF produce the same identity; historical suites still run |
| Existing immutable boxes are not treated as migrated | Operational retirement rule | Any future recovery tool | New code incorrectly assumes authority over an old box | Recovery requires a separate inventory and replay of an authorization branch in the exact old tree |

## Recovery boundary

If a legacy box is discovered, first identify its exact proposition bytes,
network, confirmation and reorg status, role tokens, and register schema. Any
exit or migration must be authorized by the box's existing ErgoTree. Merely
moving these sources or removing their builders creates no spend authority.
