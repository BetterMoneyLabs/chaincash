Different ChainCash variants for offchain applications.


In most cases, reserves are on-chain, notes are created and making progress offchain.

## Basis contract generations

- `basis.es` and `basis-token.es` are the existing v1 sources.
- `basis-v2.es` and `basis-token-v2.es` are a separate candidate family with a
  versioned claim domain, mandatory prior-state proofs, exact creditor payouts,
  fixed emergency height and input-specific successor lineage.

See `basis-v2.md` for the v2 ABI and migration boundary, and
`basis-v2-reproducibility.md` for the exact source-to-ErgoTree receipt. V2 is a
review candidate; the repository does not claim deployment or automatic
migration of v1 boxes.
