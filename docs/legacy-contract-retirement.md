# Legacy ChainCash prototype retirement

## Status

The original `onchain` reserve/note/receipt family and the experimental
`layer2-old` family are retired from the production build. Their exact sources
remain test-only historical fixtures. Production code must not compile them,
derive or print their addresses, or build transactions that activate them.

This is a quarantine boundary, not an in-place contract repair. An immutable
box already protected by one of the old ErgoTrees would still be governed by
that old tree. The repository does not contain a complete deployment inventory,
so this change makes no claim that no such boxes exist.

## Production boundary

- `Constants` exposes only the Basis and Basis-token entries through
  `publishedContracts`.
- The production printer derives its output exclusively from that registry.
- The old reserve/note construction helpers and the legacy scan-rule printer
  are absent from the production classpath.
- The old ErgoScript files live under `src/test/resources` and are loaded only
  by historical tests. Removed Scala helpers remain recoverable from the pinned
  Git history, but are not copied into a runtime or resource artifact.
- Read-only prototype tracking remains available for inventory work, but it is
  not a supported admission, construction, settlement, or migration path.

## Validation matrix

| Invariant | Producer / enforcement | Consumer | Failure if relaxed | Regression |
| --- | --- | --- | --- | --- |
| No legacy-specific source, tree, or address getter is exported | `Constants` production API | Address and deployment tooling | A caller can accidentally publish an unreviewed legacy P2S | Reflection rejects every retired getter |
| Address output is allowlisted | `publishedContracts` | `Printer` | A historical address can be presented as deployable | Captured printer output contains only the two registry entries |
| Direct legacy activation helpers are not compiled | SBT source layout | Downstream applications | Old transaction builders or scan-rule generation remain callable | Clean test classpath cannot load the four retired classes |
| Historical regression inputs remain exact | Test-resource SHA-256 manifest | `ChainCashSpec` | Tests silently exercise changed source while retaining the old name | All seven fixture digests are checked before use; the historical suite still runs |
| Existing immutable boxes are not treated as migrated | Operational retirement rule | Any future recovery tool | New code incorrectly assumes authority over an old box | Recovery requires a separate inventory and replay of an authorization branch in the exact old tree |

## Recovery boundary

If a legacy box is discovered, first identify its exact proposition bytes,
network, confirmation and reorg status, role tokens, and register schema. Any
exit or migration must be authorized by the box's existing ErgoTree. Merely
moving these sources or removing their builders creates no spend authority.
