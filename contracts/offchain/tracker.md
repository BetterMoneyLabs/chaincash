# Tracker - Debt State Monitoring and Witness Service

## Overview

Take Lightning network, for example. Using ChainCash terminology, an on-chain reserve there made by a peer A allows
only for payments between peers A and B set in advance. Which is perfect from trust assumptions point of view, but results
in capital inefficiency, quite centralized overall network topology (with few hubs dominating in liquidity flows) etc.

As an alternative, we propose to use the same reserve for potentially unbounded number of peers, even more, and allow
for interactions to happen with no reserve deployed on-chain yet even. Then, when A offers an IOU note backed by A's
reserve to B as a mean of payment, B needs to know the full state of A's debt to get an idea of collateralization.

That is the first task of the tracker - it tracks state of debt for any issuer (having on-chain reserve or not) and
reports it publicly. There is another role - as A can always issue debt to self (a generated identity A') and then
redeem before others, to prevent this, tracker should not witness new debt which is violating collateralization of
previous debt holders (and only witnessed debt is redeemable while tracker is online, emergency exit against
last witnessed state is possible when tracker is going offline only).

## Tracker Responsibilities

1. **Debt State Tracking**: Maintain the complete state of debt for all issuers in the system
2. **Note Witnessing**: Sign IOU notes to certify they are included in the tracker's state
3. **Collateralization Monitoring**: Ensure new debt does not violate collateralization of existing debt holders
4. **Public Reporting**: Publish debt state and collateralization status via NOSTR protocol
5. **On-Chain Commitment**: Periodically commit state digests to the blockchain for emergency redemption

## Tracker Operations

The tracker publishes the following events via NOSTR protocol:

- **note**: New or updated note, along with proof of tracker state transformation and digest after operation
- **redemption**: Redemption done from a reserve
- **reserve top-up**: Reserve collateral increased
- **commitment**: Data for on-chain tracker state commitment update (header, proof of UTXO against header, UTXO with commitment)
- **80% alert**: Debt level of some pubkey reaching 80% of collateral
- **100% alert**: Debt level of some pubkey reaching 100% of collateral

## Tracker API

The tracker supports the following API requests:

- **getNotesForKey**: Returns all notes associated with a pubkey
- **getProof**: Get proof for a note against latest digest published by the tracker (not necessarily committed on-chain)
- **getKeyStatus**: Returns current collateralization of a pubkey along with other important information
- **POST noteUpdate**: Create or update a note

## Security Properties

### Tracker Cannot Steal
The tracker cannot steal money from reserves because:
- IOU notes require the issuer's signature for redemption
- Tracker signature only certifies the note is witnessed, not authorizes spending
- Reserve contracts verify issuer signatures independently

### Emergency Exit
If the tracker goes offline:
- After emergency period (3 days / 2160 blocks), notes can be redeemed WITHOUT tracker signature
- Emergency redemption uses different message format: `key || totalDebt || timestamp || 0L`
- Normal redemption (with tracker signature): `key || totalDebt || timestamp`
- Different message formats prevent replay attacks between normal and emergency redemption
- Users can redeem against reserves using the last committed state snapshot
- Reserve owner's signature is still required (proves debt validity)
- Tracker signature is optional after emergency period (enables escape from tracker unavailability)

### Anti-Censorship
If the tracker starts censoring notes associated with a public key:
- Notes that were previously witnessed but removed can still be redeemed
- Protection mechanisms allow redemption of tracked notes that are no longer tracked

## Tracker Data Structure

The tracker maintains an AVL tree with the following structure:
- **Key**: `Blake2b256(payerPublicKey || payeePublicKey)` (32 bytes)
- **Value**: `(totalDebt, timestamp)` where:
  - `totalDebt`: Total amount owed (8 bytes, Long)
  - `timestamp`: Timestamp of latest payment (8 bytes, Long)

This allows efficient lookup and proof generation for any debt relationship.

## Implementation

See:
- `TrackingUtils.scala` - Core tracking logic for scanning blockchain and maintaining local state
- `TrackerBoxSetup.scala` - Utility for creating tracker box with initial AVL tree
- `basis.es` - On-chain basis contract that verifies tracker signatures and AVL proofs
