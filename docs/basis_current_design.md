# Basis Current Design - Transparent Off-Chain Cash System

This document provides a technical description of the current Basis implementation, an off-chain IOU (I Owe You) cash system backed by on-chain ERG reserves with transparent note tracking.

## Overview

Basis is an off-chain payment system designed for micropayments, content payments, P2P networks, and community currencies. It allows users to create credit (unbacked IOU money) while also supporting fully backed payments through on-chain ERG reserves. The system uses minimally-trusted trackers to maintain off-chain state while leveraging on-chain contracts for redemption security.

## Roles

### Reserve Holder
- **Identity**: Identified by a public key (secp256k1 elliptic curve point)
- **Responsibilities**:
  - Creates and maintains on-chain reserve boxes containing ERG collateral
  - Signs off-chain debt notes to creditors
  - Can top up reserves at any time
  - Liable for redemptions against their reserve

### Tracker
- **Identity**: Identified by an NFT token ID stored in reserve contracts (R6 register)
- **Responsibilities**:
  - Maintains a key-value dictionary of all debt relationships: `hash(AB) → (amount, timestamp, sig_A)`
  - Collects and validates signed off-chain notes from users
  - Periodically commits state digest on-chain using an AVL tree
  - Provides proofs for note redemptions
  - Publishes events via NOSTR protocol (note updates, redemptions, alerts)
  - Tracks collateralization levels and issues alerts (80%, 100% debt levels)

**Trust Model**: Trackers are  minimally trusted. They cannot:
- Steal funds (signatures are verified on-chain)
- Prevent emergency redemptions (after 7-day timeout, tracker signature not required)

Malicious behavior (censorship, collusion) leaves on-chain footprints and can be detected.

### Users (Debtors and Creditors)
- **Identity**: Public key on secp256k1 curve
- **Responsibilities**:
  - Create and sign debt notes when making payments
  - Maintain records of notes received as creditors
  - Initiate redemptions against reserves when desired
  - Monitor tracker state and collateralization levels

## On-Chain State

### Reserve Contract Box
The Basis reserve contract (`contracts/offchain/basis.es`) manages on-chain collateral and enforces redemption rules.

**Box Structure**:
- **Value**: ERG amount backing off-chain debts
- **Token #0**: Singleton NFT identifying this specific reserve
- **R4**: Owner's public key (GroupElement) - the reserve holder
- **R5**: AVL tree of redeemed timestamps to prevent double-spending
- **R6**: Tracker NFT ID (bytes) - identifies the authorized tracker

**Contract Actions**:

1. **Redemption (action = 0)**:
   - Validates tracker identity matches R6
   - Verifies reserve owner's Schnorr signature over `(key, amount, timestamp)`
   - Verifies tracker's Schnorr signature over the same message (or allows bypass after 7 days)
   - Checks debt amount is sufficient for redemption
   - Inserts `key → timestamp` into R5 AVL tree to mark note as redeemed
   - Transfers redeemed ERG to creditor's output
   - Preserves reserve box with reduced ERG value

2. **Top-Up (action = 1)**:
   - Allows anyone to add ERG to reserve (minimum 1 ERG)
   - Preserves all registers unchanged
   - Increases reserve box ERG value

**Double-Spend Protection**:
The AVL tree in R5 stores `hash(AB) → timestamp` pairs. When a note is redeemed:
- The timestamp is recorded in the tree
- Future redemptions with `timestamp ≤ recorded_timestamp` are rejected
- The tree has insert-only semantics to prevent removal of spent records

### Tracker Box (Off-Chain State Commitment)
While the tracker's full state lives off-chain, it periodically commits to the blockchain:
- **Token #0**: Tracker NFT
- **R4**: Tracker's public key
- **R5**: AVL tree digest (commitment to all debt records)

This commitment enables proof-based redemptions and provides an on-chain audit trail.

## Off-Chain Note Life Cycle

### Note Structure
An off-chain debt note from A to B is represented as:
```
(B_pubkey, amount, timestamp, sig_A)
```

**Fields**:
- `B_pubkey`: Creditor's public key (recipient)
- `amount`: **Total cumulative debt** of A to B (not incremental)
- `timestamp`: Monotonically increasing timestamp of latest payment (milliseconds)
- `sig_A`: Schnorr signature by A over `(hash(AB), amount, timestamp)`

where `hash(AB) = blake2b256(A_pubkey_bytes || B_pubkey_bytes)`

**Key Properties**:
- Only **one updateable note** exists per debtor-creditor pair
- Each payment increases `amount` and `timestamp`
- The note is **not transferable** - it represents A's debt specifically to B

### Issuance
1. Debtor A wants to pay creditor B
2. A retrieves current `(B, amount_old, timestamp_old, sig_old)` record from tracker (or initializes to 0)
3. A creates new note with:
   - `amount_new = amount_old + payment_amount`
   - `timestamp_new = current_time` (must be > timestamp_old)
4. A signs: `sig_A = SchnorrSign(A_secret, hash(AB) || amount_new || timestamp_new)`
5. A sends `(B_pubkey, amount_new, timestamp_new, sig_A)` to tracker
6. Tracker validates signature and updates its dictionary:
   - `hash(AB) → (amount_new, timestamp_new, sig_A)`
7. Tracker publishes update event via NOSTR

### Transfer
Notes are **not transferred** in the peer-to-peer sense. Instead:
- Each payment creates/updates a direct debt relationship
- If B wants to pay C using A's credit, B creates a new debt to C
- The system tracks bilateral debts, not circulating bearer notes
- This makes transactions **fully transparent** and linkable

### Redemption
1. Creditor B decides to redeem A's debt from A's reserve
2. B requests proof from tracker: `proof = MerkleProof(hash(AB) → (amount, timestamp))`
3. B constructs redemption transaction with:
   - **Input**: A's reserve box
   - **Data input**: Tracker box (for signature verification)
   - **Context variables**:
     - `v0 = 0` (action: redemption, index: 0)
     - `v1 = B_pubkey`
     - `v2 = sig_A` (reserve owner's signature)
     - `v3 = amount` (debt amount to redeem)
     - `v4 = timestamp`
     - `v5 = AVL_insert_proof` (proof for updating spent tree)
     - `v6 = sig_tracker` (tracker's signature authorizing redemption)
   - **Outputs**:
     - Updated reserve box with `amount` ERG removed, R5 tree updated
     - Redemption output to B with `amount` ERG

4. Contract validates:
   - Tracker NFT matches R6
   - Schnorr signatures (reserve owner and tracker) are valid
   - `amount` ≤ reserve ERG value
   - `timestamp` not already in R5 tree
   - AVL tree proof correctly inserts timestamp

5. **Emergency Redemption** (if tracker offline):
   - After 7 days from `timestamp`, B can redeem without valid tracker signature
   - Prevents tracker censorship

6. **Post-Redemption**:
   - A and B should coordinate off-chain to deduct redeemed amount
   - Next payment: `amount_new = amount_old - redeemed + new_payment`
   - System does not automatically update debt records

## Why This Scheme Is Fully Transparent

The current Basis design provides **no privacy** for the following reasons:

### 1. Transparent Identity Linkage
- All notes use **plaintext public keys** for debtors and creditors
- `hash(AB)` deterministically links A and B's identities
- Anyone with tracker access can identify who owes whom

### 2. Transparent Amount and Timing
- Debt amounts are stored in plaintext in tracker state
- Timestamps are plaintext and monotonically increasing
- Payment amounts can be inferred from `amount_new - amount_old`

### 3. Transparent Transaction Graph
- The tracker's dictionary reveals the entire debt graph
- Observers can see all bilateral debt relationships
- Payment flows between users are fully traceable

### 4. On-Chain Linkage
- Redemptions reveal:
  - Reserve holder's identity (R4 public key)
  - Creditor's public key (context variable v1)
  - Exact amount redeemed
  - Timing of redemption
- The R5 tree accumulates a permanent record of `hash(AB)` pairs
- Anyone monitoring the blockchain can link reserve holders to creditors

### 5. Tracker Omniscience
- The tracker sees all notes, all users, all amounts, all timestamps
- Tracker can perform complete transaction graph analysis
- Tracker knows exact collateralization of every user

### 6. No Unlinkability Between Actions
- Issuing a note, transferring value, and redeeming are all linkable:
  - Same public keys used throughout
  - Same `hash(AB)` used for tracker storage and on-chain redemption
  - No blinding, mixing, or anonymity sets

## Summary

The current Basis system is a **transparent off-chain credit system** where:
- All debt relationships are publicly known (to the tracker and potentially others)
- All amounts and timestamps are plaintext
- On-chain redemptions link reserve holders to creditors
- There is no mechanism for unlinkable payments or anonymous redemptions

This transparency is a fundamental limitation that prevents use cases requiring privacy, such as:
- Confidential commercial payments
- Anonymous micropayments for sensitive content
- Privacy-preserving remittances
- Untraceable agent-to-agent payments

The design prioritizes simplicity, security, and auditability over privacy. Moving to a Chaumian e-cash style private scheme would fundamentally alter the note structure, protocol flows, and trust assumptions while maintaining the core on-chain reserve and redemption architecture.
