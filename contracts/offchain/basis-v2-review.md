# Basis v2 review packet

This packet describes the exact local candidate implemented by
`basis-v2.es` and `basis-token-v2.es`. It is intended to make contract and
runtime review reproducible without treating a green mockchain suite as a
deployment claim.

## Box and branch topology

The reserve input is the sole authority for its continuing state. Redemption
reads tracker data input 0 only before the immutable R8 boundary. A continuing
branch creates one successor at the context-selected output index. Redemption
also creates one creditor payout immediately after that successor. Miner fees
and wallet change are outside the reserve accounting equations.

| Action | Authorization | Continuing state | Released value |
| --- | --- | --- | --- |
| 0 redemption before R8 | debtor claim signature, tracker NFT/tree/signature, creditor transaction proof | exact v2 successor and authenticated R5 update | exact P2PK payout bound to `SELF.id` |
| 0 redemption at/after R8 | debtor claim signature and creditor transaction proof; no tracker input/proof/signature | same successor and R5 rules | same exact payout |
| 1 top-up | permissionless | exact state preservation plus input-specific R9 | ERG or reserve tokens may only increase |
| 2 refund initiation | reserve-owner transaction proof | exact state, R7 set within the current 30-block construction window | none |
| 3 refund completion | reserve-owner transaction proof after 43,200 blocks | terminal; no successor required | owner controls the signed terminal transaction |

## Invariant and consumer matrix

| ID | Invariant / enforcement | Downstream consumer | Relaxation failure | Focused evidence |
| --- | --- | --- | --- | --- |
| I-01 | Claim key commits v2 tag, reserve NFT, tracker NFT, owner, receiver and token id where applicable. Both signatures and tracker AVL use that key. | tracker builder, note signer, both reserve contracts | one signed liability becomes valid in another reserve, asset or generation | wrong-reserve and wrong-domain signatures rejected independently |
| I-02 | Context 7 always supplies an authenticated R5 membership or non-membership proof. | R5 `insertOrUpdate` and later redemptions | existing state is treated as an unauthenticated zero baseline | omitted proof and stale absence proof rejected |
| I-03 | R5 values are fixed 24-byte `(timestamp,totalDebt,redeemed)` tuples; same claim may advance `redeemed`, newer claims cannot reduce debt. | partial-redemption builder and restart reconstruction | repeated settlement is blocked or redeemed progress is erased | two consecutive partial settlements use the original signatures |
| I-04 | Payout is at `successorIndex + 1`, has receiver P2PK bytes, R4=`SELF.id`, and exact released amount. | creditor wallet and multi-input batcher | redirected, underpaid or reused payout | receiver, amount and lineage mutations rejected independently |
| I-05 | ERG redemption requires `successor.value + payout.value == SELF.value`. | reserve collateral and fee builder | reserve collateral silently pays fees/change | reserve-funded fee mutation rejected; external fee input accepted |
| I-06 | Token successor preserves ERG, singleton and reserve-token ids; reserve-token decrease equals the payout's sole token amount. | token creditor, later reserve transitions and refund | ERG drain, token leakage or accidental burn | top-up ERG drain, redemption ERG drain and payout leakage rejected |
| I-07 | Before R8, tracker input has the exact singleton, typed key/tree, fixed tree shape, sufficient committed cumulative debt and valid signature. | normal-redemption liveness and liability admission | forged tracker state or uncommitted claim | exact normal redemption passes; cross-domain evidence rejects |
| I-08 | R8 is mandatory and exactly preserved; tracker fields are not evaluated only when `HEIGHT >= R8`. | emergency builder and acceptance policy | mutable tracker creation height postpones exit, or missing tracker still blocks the branch | tracker-free redemption passes at R8 and rejects one block before |
| I-09 | Reserve singleton shape is mandatory; every successor R9 and payout R4 equal the predecessor box id. | indexer lineage and multi-input settlement | tokenless state or one output satisfies two inputs | tokenless state and shared-successor transaction rejected |
| I-10 | Refund R7 is one-shot, owner-authorized, preserved on other branches and checked with subtraction rather than overflowing addition. | creditor monitoring and terminal refund | backdated/reset timer or overflow-assisted early refund | initiation, mature completion, early completion and `Long.MaxValue` seed exercised |
| I-11 | Constants compile exact normalized source and tests compare full output bytes with committed goldens. | compiler, builders and contract-family selection | readable source, configured P2S and runtime ABI silently diverge | source equality, fresh compile and full-byte golden tests |

Each negative transaction changes only the field named in its test while its
proofs, signatures, tree roots, tokens and unrelated outputs remain those of a
valid control. The property that groups payout mutations constructs and reduces
three independent transactions; it does not use one compound malformed box.

## Token and register authority

- ERG reserve tokens: exactly `[reserveNft -> 1]`.
- Token reserve tokens: exactly
  `[reserveNft -> 1, reserveAsset -> positiveAmount]`; ids must differ.
- Tracker data-input tokens: exactly `[configuredTrackerNft -> 1]`.
- Reserve R4-R9 types and meaning are frozen in `basis-v2.md`.
- Reserve R5: key length 32, value length 24, insert/update enabled, remove
  disabled.
- Tracker R5: key length 32, value length 8, insert/update enabled, remove
  disabled.

## Test closure

`sbt -batch "testOnly chaincash.BasisV2Spec"` currently executes 15 tests. They
cover normal and emergency redemption, domain separation, proof omission and
replay, exact payouts, ERG/token conservation, partial settlement, singleton
and output injectivity, both top-up branches, both refund families, source
linkage and full golden bytes.

The unchanged v1 suites remain a separate regression gate:

```text
sbt -batch "testOnly chaincash.BasisSpec chaincash.BasisTokenSpec"
```

## Evidence vector and residual gates

| Dimension | Status |
| --- | --- |
| Implementation | matrix-covered by local source and 15 focused mockchain tests |
| Independent review | pending on the exact candidate commit |
| CI | not run |
| Target node | not run; no live or broadcast action authorized |
| Readiness | local draft for maintainer review |

Remaining integration obligations are explicit:

- the Rust tracker, note schema, signer and redemption builders must implement
  the exact domain key, 24-byte redeemed value, fixed-length AVL metadata,
  mandatory context 7 proof, payout R4 marker and R8 semantics;
- reserve admission must require a genuine singleton, a sufficiently distant
  R8 and the exact golden ErgoTree; emergency mode intentionally relies on
  visible seed policy because the debtor signature is the remaining liability
  authority after R8;
- tracker-key rotation and old/new family coexistence need an explicit runtime
  policy; no implicit v1/v2 aggregation is allowed;
- v1 contains no migration authorization, so live v1 boxes, if any, must be
  inventoried and handled only through their existing branches;
- reduction cost, serialized transaction size, independent clean rebuild,
  target-node check, wallet/signer compatibility, reorg/indexer handling and
  deployment recovery remain unverified.
