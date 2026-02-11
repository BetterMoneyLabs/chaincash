# Privacy-Preserving Off-Chain Cash: Chaumian E-Cash Extension

## Executive Summary

This document describes a proof-of-concept extension to the ChainCash/Basis system that adds privacy-preserving off-chain cash using techniques inspired by Chaumian e-cash. The design maintains full auditability of on-chain reserves while providing unlinkability for off-chain transfers.

**Key Properties:**
- ✅ On-chain reserves remain fully auditable
- ✅ Off-chain transfers are unlinkable
- ✅ Double-spending prevented via serial number tracking
- ✅ Minimal changes to existing architecture
- ⚠️ POC-level cryptography (simplified for clarity)

## Table of Contents

1. [Overview](#overview)
2. [Protocol Design](#protocol-design)
3. [Smart Contract Changes](#smart-contract-changes)
4. [Off-Chain Flow](#off-chain-flow)
5. [Privacy Analysis](#privacy-analysis)
6. [Threat Model](#threat-model)
7. [Limitations and Future Work](#limitations-and-future-work)
8. [Comparison with Current System](#comparison-with-current-system)

## Overview

### Current System (Transparent)

The existing ChainCash/Basis system provides:
- **On-chain reserves**: ERG collateral backing issued notes
- **On-chain notes**: Transparent tokens with spending chains
- **Off-chain Basis**: Debt/credit tracking via trackers
- **Full transparency**: All transfers visible on-chain

### Privacy Extension Goals

The privacy-preserving extension adds:
- **Blind signature minting**: Users mint cash without revealing serial numbers
- **Unlinkable transfers**: Off-chain transfers don't reveal sender/receiver/amount linkage
- **Serial number redemption**: Double-spending prevented via on-chain serial tracking
- **Reserve auditability**: On-chain reserves remain fully transparent

### Architecture Principles

1. **Minimal Changes**: Extend existing contracts, don't rewrite
2. **Backward Compatible**: Existing transparent system continues to work
3. **POC Focus**: Clarity over production hardness
4. **Single Issuer**: Assumes honest-but-curious reserve operator

## Protocol Design

### High-Level Flow

```
┌─────────┐         ┌──────────┐         ┌─────────┐
│  User   │────────▶│ Reserve  │────────▶│  User   │
│  Mint   │  Blind  │  Signs   │ Unblind │  Token  │
└─────────┘ Request └──────────┘ Response└─────────┘
     │                                                    │
     │                                                    │
     ▼                                                    ▼
┌─────────┐         ┌──────────┐         ┌─────────┐
│ Sender │────────▶│ Off-chain│────────▶│Receiver │
│ Token  │ Transfer│  (P2P)   │ Receive │  Token  │
└─────────┘         └──────────┘         └─────────┘
     │                                                    │
     │                                                    │
     └────────────────────────────────────────────────────┘
                          │
                          ▼
                    ┌──────────┐
                    │ Reserve  │
                    │ Redeem   │
                    │ (On-chain│
                    └──────────┘
```

### Cryptographic Components

#### 1. Blind Signatures

**Purpose**: Allow reserve to sign commitments without seeing serial numbers.

**Simplified POC Scheme**:
- User creates commitment: `C = H(serial || secret)`
- User blinds: `B = C * r^e` where `r` is random blinding factor
- Reserve signs `B` without seeing `C`
- User unblinds to get signature on `C`

**Production Note**: Use proper blind signature scheme (RSA blind signatures, BLS blind signatures, or Chaum's original scheme).

#### 2. Serial Numbers

**Purpose**: Prevent double-spending while maintaining privacy.

**Properties**:
- Unique per token
- Only revealed during redemption
- Tracked in on-chain AVL tree
- Cannot be linked to mint or transfer

#### 3. Commitments

**Purpose**: Bind serial number to secret without revealing either.

**Format**: `H(serial || secret)` where:
- `serial`: 32-byte random value
- `secret`: 32-byte user secret
- `H`: Blake2b256 hash function

### Protocol Steps

#### Step 1: Minting

```
User                                    Reserve
  │                                        │
  │─── 1. Create (serial, secret) ────────▶│
  │    blindedCommitment = blind(H(serial||secret))
  │    amount = 1000 nanoERG
  │                                        │
  │                                        │─── 2. Verify collateral
  │                                        │    Sign blindedCommitment
  │                                        │    Update issued amount
  │◀── 3. blindedSignature ────────────────│
  │                                        │
  │─── 4. Unblind signature ─────────────▶│
  │    token = (serial, secret, signature, amount)
  │                                        │
```

**Privacy**: Reserve never sees `serial` or `secret`, only blinded commitment.

#### Step 2: Transfer (Off-Chain)

```
Sender                                  Receiver
  │                                        │
  │─── 1. Transfer message ───────────────▶│
  │    token = (serial, secret, sig, amount)
  │    (In production: include ZK proofs)
  │                                        │
  │                                        │─── 2. Verify signature
  │                                        │    Accept token
  │◀── 3. Acknowledgment ──────────────────│
  │                                        │
```

**Privacy**: 
- No on-chain transaction
- Sender/receiver identities hidden
- Amount hidden (in full implementation with ZK proofs)
- Transfer unlinkable to mint or other transfers

#### Step 3: Redemption (On-Chain)

```
User                                    Reserve Contract
  │                                        │
  │─── 1. Reveal (serial, secret, sig) ───▶│
  │    amount = 1000 nanoERG
  │                                        │
  │                                        │─── 2. Verify signature
  │                                        │    Check serial not in spent tree
  │                                        │    Add serial to spent tree
  │                                        │    Redeem ERG (minus fee)
  │◀── 3. ERG transferred ────────────────│
  │                                        │
```

**Privacy**: Serial number only revealed once, at redemption time.

## Smart Contract Changes

### New Contract: `privacy_reserve.es`

**Location**: `contracts/onchain/privacy_reserve.es`

**Key Features**:
- Extends existing reserve contract structure
- Adds serial number tracking via AVL tree
- Supports blind signature minting
- Enforces double-spend prevention

**Registers**:
- `R4`: Reserve owner's public key (same as existing)
- `R5`: AVL tree of spent serial numbers (insert-only)
- `R6`: Total issued amount (for auditing)

**Actions**:
- `action = 0`: Mint privacy cash (blind signature)
- `action = 1`: Redeem privacy cash (serial verification)
- `action = 2`: Top up reserve (same as existing)

### Contract Logic

#### Mint Action (action = 0)

```ergoscript
// User provides blinded commitment
val blindedCommitment = getVar[GroupElement](2).get

// Reserve signs blinded commitment
val properBlindSignature = verifySignature(blindedCommitment, ownerKey)

// Update issued amount
val newIssued = currentIssued + mintAmount
val issuedUpdated = selfOut.R6[Long].get == newIssued

// Verify sufficient collateral
val sufficientCollateral = selfOut.value >= newIssued
```

#### Redeem Action (action = 1)

```ergoscript
// User reveals serial number
val serialNumber = getVar[Coll[Byte]](1).get

// Verify signature on H(serial || secret)
val properSignature = verifySignature(commitment, ownerKey)

// Check serial not spent
val spentSerials = SELF.R5[AvlTree].get
val serialNotSpent = spentSerials.get(serialNumber, proof).isEmpty

// Add serial to spent tree
val nextTree = spentSerials.insert(serialNumber, proof).get

// Redeem ERG (with fee)
val actualRedeemed = SELF.value - selfOut.value
val feeCorrect = actualRedeemed <= (redeemAmount * 98) / 100
```

### Integration with Existing System

The privacy-preserving contract can coexist with existing transparent contracts:
- Same reserve can support both transparent and privacy cash
- Users choose which system to use per transaction
- No breaking changes to existing functionality

## Off-Chain Flow

### Implementation Files

1. **`PrivacyCashUtils.scala`**: Core cryptographic operations
2. **`PrivacyCashProtocol.scala`**: High-level protocol flows

### Key Functions

#### Minting

```scala
// User creates mint request
val (request, serial, secret) = 
  userCreateMintRequest(amount, reservePubKey)

// Reserve signs (off-chain or via contract)
val response = reserveSignMintRequest(request, reserveSecretKey)

// User processes response
val token = userProcessMintResponse(
  response, serial, secret, amount, 
  reservePubKey, blindingFactor
)
```

#### Transfer

```scala
// Sender prepares transfer
val (transferMsg, changeToken) = 
  senderPrepareTransfer(token, recipientAmount)

// Receiver verifies
val isValid = receiverVerifyTransfer(transferMsg)
```

#### Redemption

```scala
// User prepares redemption
val redemptionData = userPrepareRedemption(token)

// On-chain transaction (see contract)
// Contract verifies and redeems
```

### Data Structures

```scala
case class PrivacyCashToken(
  serialNumber: Array[Byte],      // Revealed only on redemption
  secret: Array[Byte],             // User's secret
  signature: (GroupElement, BigInt), // Reserve's signature
  amount: Long,                    // Amount in nanoERG
  reservePubKey: GroupElement      // Reserve identifier
)
```

## Privacy Analysis

### Privacy Properties

#### 1. Unlinkability

**Mint to Transfer**: 
- ✅ Unlinkable: Serial number not revealed during mint
- ✅ Unlinkable: Transfer doesn't reveal mint information

**Transfer to Transfer**:
- ✅ Unlinkable: Each transfer is independent
- ⚠️ Limited: Without mixing, timing analysis possible

**Transfer to Redemption**:
- ✅ Unlinkable until redemption: Serial only revealed once
- ⚠️ Linkable at redemption: Serial revealed on-chain

#### 2. Anonymity Set

**Current POC**:
- Anonymity set = all users with same amount tokens
- Limited by amount granularity

**With Full Implementation**:
- Anonymity set = all users in system
- Improved with mixing networks or ZK proofs

#### 3. Issuer Knowledge

**What Issuer Knows**:
- ✅ Total issued amount (on-chain)
- ✅ Blinded commitments during mint (cannot see serials)
- ✅ Serial numbers during redemption (only at redemption time)
- ❌ Cannot link mint to transfer
- ❌ Cannot link transfer to transfer
- ⚠️ Can link redemption to serial (but not to original mint)

**What Issuer Doesn't Know**:
- ❌ Serial numbers during mint
- ❌ Transfer participants
- ❌ Transfer amounts (in full implementation)
- ❌ Link between mint and redemption

### Privacy Comparison

| Property | Transparent System | Privacy Extension |
|----------|-------------------|-------------------|
| Mint visibility | ✅ Public | ✅ Blinded |
| Transfer visibility | ✅ On-chain | ✅ Off-chain |
| Amount privacy | ❌ Public | ⚠️ POC: Limited, Full: Yes |
| Sender privacy | ❌ Public | ✅ Hidden |
| Receiver privacy | ❌ Public | ✅ Hidden |
| Redemption linkability | ✅ Linkable | ⚠️ Linkable at redemption |

## Threat Model

### Assumptions

1. **Honest-but-Curious Issuer**: Reserve follows protocol but may try to learn information
2. **Single Issuer**: One reserve operator (can extend to multiple)
3. **POC Cryptography**: Simplified schemes for clarity
4. **Offline Double-Spend Detection**: Serial numbers checked on-chain

### Threats and Mitigations

#### 1. Double-Spending

**Threat**: User spends same token twice.

**Mitigation**:
- Serial numbers tracked in on-chain AVL tree
- Redemption requires serial not in spent tree
- On-chain verification prevents double-spend

**Limitation**: Requires on-chain redemption (offline double-spend possible until redemption).

#### 2. Issuer Tracking

**Threat**: Issuer tries to link mints to redemptions.

**Mitigation**:
- Blind signatures prevent issuer seeing serials during mint
- Serial only revealed at redemption
- Cannot link mint to transfer

**Limitation**: Issuer sees serial at redemption (can link redemption to serial, but not to mint).

#### 3. Transfer Linkability

**Threat**: Adversary tries to link transfers.

**Mitigation**:
- Off-chain transfers don't reveal on-chain information
- Serial numbers not revealed during transfer
- No on-chain transaction for transfers

**Limitation**: Timing analysis and network analysis possible (mitigated with mixing).

#### 4. Reserve Insolvency

**Threat**: Reserve doesn't have enough collateral.

**Mitigation**:
- On-chain contract checks `reserve.value >= issued`
- Transparent reserve balance
- Auditable issued amount

**Limitation**: Requires monitoring and governance.

### Security Properties

| Property | Status | Notes |
|----------|--------|-------|
| Double-spend prevention | ✅ | On-chain serial tracking |
| Reserve auditability | ✅ | Full transparency |
| Mint privacy | ✅ | Blind signatures |
| Transfer privacy | ✅ | Off-chain, unlinkable |
| Redemption privacy | ⚠️ | Serial revealed |
| Issuer honesty | ⚠️ | Assumed honest-but-curious |

## Limitations and Future Work

### Current Limitations (POC)

1. **Simplified Cryptography**:
   - Blind signature scheme is simplified
   - Commitment encoding is simplified
   - Not production-ready

2. **Limited Anonymity**:
   - Amount-based anonymity sets
   - No mixing network
   - Timing analysis possible

3. **Redemption Linkability**:
   - Serial revealed on-chain
   - Can link redemption to serial (but not to mint)

4. **Single Issuer**:
   - Assumes one reserve operator
   - No multi-issuer support

5. **Offline Double-Spend**:
   - Double-spend possible until on-chain redemption
   - No real-time double-spend detection

### Future Work

#### 1. Production Cryptography

- **Proper Blind Signatures**: Implement RSA blind signatures or BLS blind signatures
- **Hash-to-Curve**: Proper encoding of commitments as group elements
- **ZK Proofs**: Zero-knowledge proofs for amount validity in transfers

#### 2. Enhanced Privacy

- **Mixing Networks**: Add mixing for better unlinkability
- **Amount Hiding**: ZK proofs for amount ranges without revealing exact amounts
- **Redemption Privacy**: Techniques to hide serial numbers during redemption (e.g., ZK proofs)

#### 3. Multi-Issuer Support

- **Federation**: Multiple reserve operators
- **Cross-Reserve Transfers**: Transfer between different reserves
- **Trust Aggregation**: Combine trust from multiple reserves

#### 4. Real-Time Double-Spend Detection

- **Watchtowers**: Off-chain services monitoring for double-spends
- **Light Clients**: Efficient double-spend checking
- **Gossip Protocol**: P2P network for double-spend detection

#### 5. Advanced Features

- **Partial Redemption**: Redeem part of token
- **Token Splitting**: Efficient splitting with ZK proofs
- **Expiration**: Time-based token expiration
- **Recovery**: Mechanisms for lost tokens

## Comparison with Current System

### Architecture Comparison

| Aspect | Transparent System | Privacy Extension |
|--------|-------------------|-------------------|
| **On-Chain** | Notes as tokens | Reserves only |
| **Off-Chain** | Basis trackers | Privacy cash tokens |
| **Minting** | On-chain transaction | Blind signature (off-chain request, on-chain signature) |
| **Transfer** | On-chain transaction | Off-chain (no blockchain) |
| **Redemption** | On-chain transaction | On-chain transaction |
| **Privacy** | None | Unlinkable transfers |
| **Auditability** | Full | Reserves only |

### Use Case Comparison

#### Transparent System
- ✅ Full auditability
- ✅ On-chain verification
- ✅ Public transparency
- ❌ No privacy

#### Privacy Extension
- ✅ Transfer privacy
- ✅ Unlinkable payments
- ✅ Off-chain efficiency
- ⚠️ Limited auditability (reserves only)

### When to Use Which

**Use Transparent System When**:
- Auditability is required
- Regulatory compliance needed
- Public transparency desired
- On-chain verification needed

**Use Privacy Extension When**:
- Privacy is required
- Off-chain efficiency needed
- Unlinkable payments desired
- Reserve auditability sufficient

## Implementation Notes

### File Structure

```
contracts/onchain/
  ├── privacy_reserve.es          # Privacy-preserving reserve contract
  └── reserve.es                   # Existing transparent reserve (unchanged)

src/main/scala/chaincash/offchain/
  ├── PrivacyCashUtils.scala       # Core cryptographic utilities
  └── PrivacyCashProtocol.scala   # High-level protocol flows

docs/
  └── chaumian-offchain-cash.md    # This document
```

### Testing Considerations

**POC Testing**:
- Unit tests for cryptographic operations
- Integration tests for protocol flows
- Contract tests for on-chain logic

**Production Testing**:
- Security audit of cryptography
- Privacy analysis
- Performance benchmarking
- Attack scenario testing

### Deployment Considerations

**POC Deployment**:
- Testnet only
- Small amounts
- Limited users

**Production Deployment**:
- Security audit required
- Gradual rollout
- Monitoring and alerting
- Governance mechanisms

## Conclusion

This proof-of-concept demonstrates how Chaumian e-cash principles can be integrated into the ChainCash/Basis system to provide privacy-preserving off-chain cash while maintaining reserve auditability. The design prioritizes clarity and minimal changes to the existing architecture.

**Key Achievements**:
- ✅ Privacy-preserving off-chain transfers
- ✅ Double-spend prevention via serial numbers
- ✅ Reserve auditability maintained
- ✅ Minimal changes to existing system

**Next Steps**:
- Implement production-grade cryptography
- Add mixing networks for enhanced privacy
- Support multi-issuer scenarios
- Develop real-time double-spend detection

---

**Author**: Blockchain Engineering Team  
**Date**: 2024  
**Status**: Proof of Concept  
**Version**: 1.0

