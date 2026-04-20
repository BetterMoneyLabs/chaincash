# Private Basis Reserve Contract - Technical Documentation

This document explains the ErgoScript implementation of the private Basis reserve contract (`basis_private_reserve.es`), which enables privacy-preserving off-chain cash using Chaumian blind signatures.

## Contract Overview

The private Basis reserve contract is a modified version of the transparent Basis contract that replaces direct creditor-debtor linkage with nullifier-based redemption. This enables unlinkable bearer notes while maintaining double-spend prevention and on-chain reserve backing.

## Key Differences from Transparent Basis

| Aspect | Transparent Basis | Private Basis |
|--------|-------------------|---------------|
| **R4** | Reserve owner's key | Mint public key (for blind signatures) |
| **R5 Tree Key** | `hash(AB)` (debtor-creditor pair) | `nullifier` (anonymous identifier) |
| **R5 Tree Value** | Timestamp of last payment | Timestamp of redemption |
| **Signature Verified** | Owner signature on debt record | Blind signature on note commitment |
| **Redeemer Identity** | Fixed creditor B | Any holder with valid note |
| **Linkability** | Withdrawal ↔ Redemption linked | Unlinkable (blind signatures) |

## Contract Structure

### Inputs
- **SELF**: Reserve box being spent

### Data Inputs
- **tracker** (index 0): Tracker box containing:
  - Token #0: Tracker NFT (matches reserve R6)
  - R4: Tracker public key
  - R5: AVL tree digest of nullifier commitments

### Context Variables

#### Redemption (action 0)
- `v0`: Action/index byte (0 for redemption at index 0)
- `v1`: Receiver public key (GroupElement) - can be ephemeral
- `v2`: Blind signature bytes (33 bytes A' + 32 bytes z')
- `v3`: Denomination (Long) - amount in nanoERG
- `v4`: Serial (32 bytes) - random note identifier
- `v5`: AVL insert proof for nullifier
- `v6`: Tracker signature bytes (33 bytes A + 32 bytes z)

#### Top-Up (action 1)
- `v0`: Action/index byte (10 for top-up at index 0)

### Outputs

#### Redemption
- **OUTPUTS(index)**: Updated reserve box
  - Value: SELF.value - denom
  - R5: Tree with nullifier added
  - All other fields preserved
- **OUTPUTS(index + 1)**: Redemption payment to receiver
  - Value: denom
  - Proposition: Checked via `proveDlog(receiver)`

#### Top-Up
- **OUTPUTS(index)**: Updated reserve box
  - Value: SELF.value + top-up amount (≥ 1 ERG)
  - All registers preserved

## Cryptographic Verification Steps

### 1. Note Commitment Construction

```scala
noteCommitment = blake2b256(longToByteArray(denom) || serial)
```

The note commitment binds the denomination and serial number. This is the value that was blinded during withdrawal.

### 2. Blind Signature Verification

The contract verifies a Schnorr signature on the note commitment:

```scala
e = blake2b256(A' || noteCommitment || PK_mint)
verify: G^(z') == A' * PK_mint^e
```

**Components**:
- `A'`: Random point from signature (33 bytes, compressed)
- `z'`: Scalar response (32 bytes, big-endian)
- `PK_mint`: Mint's public key (from R4)
- `G`: Generator point

**Security**: This signature was created during withdrawal on a *blinded* commitment. The mint never saw the actual `noteCommitment`, only `C_blind = noteCommitment * G^r`. After unblinding, the user obtains a valid signature on the real commitment.

### 3. Nullifier Computation

```scala
nullifierPrefix = blake2b256("nullifier")
nullifier = blake2b256(nullifierPrefix || serial || PK_mint)
```

**Purpose**: The nullifier is a one-way function of the serial number, bound to the specific mint. This prevents:
- **Double-spending**: Same serial → same nullifier → tree insertion fails
- **Cross-reserve replay**: Different mint key → different nullifier

**Privacy**: Observers see only the nullifier (random-looking hash), not the original serial. Without knowing the serial, they cannot link the redemption back to the withdrawal.

### 4. Nullifier Tree Update

```scala
nextTree = SELF.R5[AvlTree].get.insert((nullifier, timestamp), proof)
verify: nextTree == selfOut.R5[AvlTree].get
```

**Mechanism**: The AVL tree with insert-only semantics ensures:
- Nullifier is not already present (proves note unspent)
- Nullifier is permanently recorded (prevents future double-spends)
- Tree state is committed on-chain

**Proof**: The user provides a Merkle proof that:
1. Nullifier is not in current tree
2. Insertion produces the new tree root in output

If the nullifier exists, `insert()` fails and the transaction is invalid.

### 5. Tracker Signature Verification

```scala
message = nullifier || denom || timestamp
e_tracker = blake2b256(A_tracker || message || PK_tracker)
verify: G^(z_tracker) == A_tracker * PK_tracker^(e_tracker)
```

**Purpose**: The tracker authorizes the redemption, confirming:
- Nullifier is not in the tracker's known spent set
- Redemption parameters are valid

**Bypass**: After 7 days, redemption allowed without tracker signature (emergency failsafe if tracker goes offline).

## Security Properties

### Double-Spend Prevention

1. **Nullifier Uniqueness**: Each serial number produces a unique nullifier
2. **Tree Immutability**: AVL tree is insert-only; once nullifier is added, it cannot be removed
3. **On-Chain Enforcement**: Contract rejects transactions where nullifier already exists in tree
4. **Cryptographic Binding**: Nullifier is bound to serial via one-way hash; cannot be forged

### Reserve Solvency

- **Top-Up Only**: Reserve value can only increase (action 1) or decrease by exact denom (action 0)
- **Denomination Matching**: Redeemed amount must equal note's denomination
- **No Fractional Redemption**: Cannot redeem more than note value
- **Proof-of-Reserves**: External observers can verify: `reserve_ERG ≥ sum(unspent_notes)`

### Blind Signature Security

- **Unforgeability**: Only mint with `sk_mint` can create valid signatures
- **Non-Repudiation**: Mint cannot deny signing a note (signature verifies under `PK_mint`)
- **Unlinkability**: Mint cannot link blinded commitment seen during withdrawal to nullifier revealed during redemption

## Privacy Analysis

### What the Contract Hides

1. **Withdrawal Identity**: Contract does not store who deposited ERG for withdrawal
2. **Note History**: No record of previous holders before redemption
3. **Transfer Graph**: Off-chain note transfers leave no trace in contract state

### What the Contract Reveals

1. **Nullifier**: Permanently recorded in R5 (but unlinkable to withdrawal without serial)
2. **Denomination**: Visible in redemption transaction (context var v3)
3. **Redemption Timing**: Timestamp recorded in tree
4. **Receiver Public Key**: Context var v1 (can be ephemeral to preserve privacy)

### Linkability Attacks

**On-Chain Timing**: If only one withdrawal and one redemption occur in a time window, they may be linked statistically (not cryptographically).

**Mitigation**: Use batching, random delays, and multiple concurrent users.

## Comparison to Transparent Basis Contract

### Removed Features
- `hash(AB)` debtor-creditor mapping
- Cumulative debt tracking
- Specific creditor authorization

### Added Features
- Blind signature verification
- Nullifier computation and checking
- Bearer note support (any holder can redeem)
- Privacy via unlinkability

### Preserved Features
- On-chain reserve backing
- AVL tree double-spend prevention
- Tracker authorization
- Emergency redemption timeout
- Top-up functionality

## Implementation Notes

### Simplifications in PoC

1. **Single Denomination**: Off-chain enforcement of denomination policy
2. **Timestamp Handling**: Uses redemption timestamp; note issuance timestamp not tracked on-chain
3. **Textbook Schnorr**: Production version should use RFC 8032 or similar standard
4. **No Proof Batching**: Each redemption requires separate on-chain transaction

### Production Hardening Needed

1. **Secure Schnorr Implementation**: Use constant-time operations, domain separation
2. **Denomination Registry**: On-chain list of allowed denominations
3. **Proof Aggregation**: Batch multiple redemptions into one transaction
4. **Side-Channel Resistance**: Prevent timing attacks on signature verification
5. **Formal Verification**: Mathematical proof of unlinkability and double-spend prevention

## Usage Example

### Redemption Transaction Construction

```scala
// User constructs context variables
val denom = 1000000000L  // 1 ERG
val serial = Array[Byte](/* 32 random bytes */)
val blindSig = Array[Byte](/* 65 bytes: A' || z' */)
val receiverPk = GroupElement(/* ephemeral public key */)

val nullifier = computeNullifier(serial, mintPubKey)
val avlProof = tracker.getInsertProof(nullifier)
val trackerSig = tracker.signRedemption(nullifier, denom, timestamp)

// Build transaction
val tx = new Transaction(
  inputs = Array(reserveBox),
  dataInputs = Array(trackerBox),
  outputs = Array(
    updatedReserveBox,  // R5 tree updated
    redemptionBox       // denom ERG to receiver
  ),
  contextVars = Array(
    ContextVar(0, 0.toByte),
    ContextVar(1, receiverPk),
    ContextVar(2, blindSig),
    ContextVar(3, denom),
    ContextVar(4, serial),
    ContextVar(5, avlProof),
    ContextVar(6, trackerSig)
  )
)
```

## Future Enhancements

1. **Multi-Denomination Support**: Encode denomination in R7, enforce allowed set
2. **Batch Redemptions**: Aggregate multiple nullifiers in one transaction
3. **ZK-SNARK Redemption**: Hide nullifier reveal using zero-knowledge proofs
4. **Cross-Reserve Swaps**: Atomic swaps between different reserves for anonymity set mixing
5. **Recursive Blinding**: Re-blind notes periodically to refresh unlinkability

## References

- [Basis Off-Chain Cash Specification](basis.md)
- [Chaumian E-Cash Protocol Design](basis_private_chaumian_poc.md)
- [Transparent Basis Contract](basis.es)
- [David Chaum's Blind Signatures](https://sceweb.sce.uhcl.edu/yang/teaching/csci5234WebSecurityFall2011/Chaum-blind-signatures.PDF)

---

**Note**: This is a PROOF OF CONCEPT implementation for research and demonstration purposes. Do not use in production without comprehensive security audit and cryptographic review.
