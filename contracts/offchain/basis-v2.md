# Basis reserve contracts, generation v2

Status: local candidate for maintainer review. This document defines a new
contract family; it does not change or upgrade `basis.es` or
`basis-token.es`.

## Design goals

Generation v2 makes each cumulative claim specific to one reserve and one
asset, requires authenticated AVL membership or non-membership evidence on
every redemption, binds every released asset to a creditor output, and keeps a
fixed emergency boundary in the reserve itself. It also gives every continuing
successor and payout an input-specific lineage marker so one output cannot
satisfy two reserve inputs.

The contracts deliberately preserve the v1 refund policy: an owner may announce
a full refund, creditors retain the redemption path during the waiting period,
and the owner may complete the refund after 43,200 blocks.

## Contract family and signed claim domain

The two source files are independent immutable contract generations:

| Reserve kind | Source | Domain tag (hex) | Asset component |
| --- | --- | --- | --- |
| ERG | `basis-v2.es` | `4241534953020000` | none |
| token | `basis-token-v2.es` | `4241534953020001` | reserve token id |

The tag encodes `BASIS`, ABI version 2, the Ergo-mainnet domain code `00`, and
the reserve-kind code (`00` for ERG, `01` for token).

For an ERG reserve, the 32-byte tracker/claim key is:

```text
blake2b256(domainTag || reserveNftId || trackerNftId ||
           ownerKey || receiverKey)
```

For a token reserve, `reserveTokenId` is inserted after `reserveNftId`.
The debtor and tracker sign:

```text
claimKey || longToByteArray(totalDebt) || longToByteArray(timestamp)
```

The same `claimKey` indexes the tracker AVL tree and the reserve redemption AVL
tree. A signature for another reserve NFT, reserve token, network or contract
generation therefore does not authorize this reserve.

The signed claim is cumulative, not a request for one payout. The creditor's
transaction proof and the contract's exact payout predicate authorize each
partial settlement. This permits repeated partial settlement with the original
debtor and tracker signatures.

## Reserve ABI

All registers R4 through R9 are mandatory and densely packed.

| Field | Sigma type | Meaning | Successor rule |
| --- | --- | --- | --- |
| R4 | `GroupElement` | reserve owner signing key | exact preservation |
| R5 | `AvlTree` | redeemed-claim state | branch-specific authenticated update |
| R6 | `Coll[Byte]` | tracker singleton NFT id | exact preservation |
| R7 | `Long` | refund initiation height; `0` means inactive | branch-specific, otherwise preserved |
| R8 | `Long` | fixed emergency start height | exact preservation |
| R9 | `Coll[Byte]` | predecessor box id | successor must equal `SELF.id` |

The redeemed-state tree uses 32-byte keys, fixed 24-byte values, insert and
update enabled, and remove disabled. Each value is:

```text
timestamp (8 bytes) || totalDebt (8 bytes) || cumulativeRedeemed (8 bytes)
```

Every redemption supplies both a lookup proof against the current tree and an
`insertOrUpdate` proof against that same root. A valid non-membership proof is
required for the first redemption; omission is never interpreted as absence.
For an existing claim, the same `(timestamp, totalDebt)` may be settled again,
or a strictly newer timestamp may increase (never decrease) `totalDebt`.

The ERG reserve has exactly one token: its singleton NFT at index 0 with amount
1. The token reserve has exactly two tokens: its singleton at index 0 with
amount 1 and its reserve asset at index 1. The two token ids must differ.

## Tracker ABI

Before R8, redemption requires data input 0 with exactly the configured tracker
singleton NFT, tracker public key in R4, and the committed debt AVL tree in R5.
The tracker tree uses the generation-v2 `claimKey`, 32-byte keys and fixed
8-byte values. The lookup value must be at least `totalDebt`, and the tracker
signature over the claim message must verify. This preserves an older signed
cumulative claim after the tracker commits a later, larger debt in that domain.

At or after R8, tracker data, tracker proof and tracker signature are not read.
The debtor claim signature, creditor transaction proof, reserve lookup/update
proofs, exact payout and all successor constraints remain mandatory. R8 is
immutable after reserve creation. Admission software must reject a reserve
whose emergency boundary is missing, already open, or too close for its credit
policy; the script cannot retroactively repair a malformed seed.

## Context-extension ABI

| Id | Type | Redemption meaning |
| ---: | --- | --- |
| 0 | `Byte` | packed `action * 10 + successorOutputIndex` |
| 1 | `GroupElement` | receiver key |
| 2 | `Coll[Byte]` | reserve-owner Schnorr signature |
| 3 | `Long` | cumulative total debt |
| 4 | `Long` | claim timestamp |
| 5 | `Coll[Byte]` | redeemed-tree `insertOrUpdate` proof |
| 6 | `Coll[Byte]` | tracker signature; required before R8 |
| 7 | `Coll[Byte]` | mandatory redeemed-tree lookup proof |
| 8 | `Coll[Byte]` | tracker-tree lookup proof; required before R8 |

Actions remain 0 redemption, 1 top-up, 2 refund initiation and 3 refund
completion. The packed output index is checked before access. A redemption
places its payout immediately after its successor.

## Output and conservation rules

Every continuing successor preserves the exact script, reserve identity,
owner, tracker id and emergency height, and sets R9 to `SELF.id`. Every payout
uses the receiver's P2PK proposition, stores `SELF.id` in payout R4, and equals
the reserve decrease exactly.

- ERG redemption: `successor.value + payout.value == SELF.value`; reserve ERG
  cannot fund miner fees or change.
- Token redemption: successor ERG is not lower; the reserve-token decrease
  equals the payout's sole token amount.
- ERG top-up: successor value increases by at least 0.1 ERG.
- Token top-up: reserve-token amount increases by at least one and successor
  ERG is not lower.

The predecessor marker is an injective binding: two distinct reserve inputs
have different box ids, so one successor or payout cannot satisfy both even if
they were created with a duplicated or non-singleton token id.

## Generation and migration boundary

V1 boxes remain governed exclusively by their v1 ErgoTree. The v1 contracts
require same-script successors and contain no v1-to-v2 migration branch, so
this repository does not claim that an existing v1 box can be rewritten or
migrated. V2 must be deployed as a new reserve family and indexed separately.

Builders, trackers and indexers must select one exact family manifest and reject
implicit v1/v2 mixing. A deployment receipt must bind source bytes, compiler,
tree version, constants and full ErgoTree bytes before any v2 P2S is treated as
authoritative.

## Evidence boundary

Local compiler and mockchain tests can establish source-to-byte consistency and
the tested predicates for the pinned build. They do not establish target-node
admission, wallet compatibility, deployment, live-box lineage, economic safety
or production readiness. Those remain separate review and runtime gates.
