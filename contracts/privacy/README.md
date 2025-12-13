# Privacy Contracts for ChainCash Basis

This directory contains privacy-enhanced contracts for the Basis offchain cash protocol.

## Overview

The contracts in this directory implement **unlinkable redemptions** using Chaumian blind signatures, addressing critical privacy leaks in the original Basis protocol.

## Files

### `private-basis.es`
ErgoScript contract implementing private redemptions.

**Key Features:**
- Action #3: Private redemption using blind signatures
- Nullifier-based double-spend prevention
- Schnorr signature verification
- Backward compatible with original Basis

### `blind-signature-spec.md`
Detailed cryptographic specification of the blind signature protocol.

**Contents:**
- Protocol steps
- Security analysis
- Implementation notes
- Test vectors

## Privacy Properties

| Property | Status |
|----------|--------|
| Receiver Anonymity | ✅ Achieved |
| Unlinkability | ✅ Achieved |
| Timing Privacy | ✅ Achieved |
| Amount Privacy | ❌ Future Work |

## Usage

### 1. Deploy Private Reserve

```scala
// Create reserve with private-basis.es contract
val reserveBox = createReserveBox(
  contract = privateBasesContract,
  ownerKey = myPublicKey,
  collateral = 10 * ERG,
  nullifierTree = emptyAVLTree
)
```

### 2. Issue Blind Signature (Offchain)

```scala
// Receiver generates blind request
val nonce = generateNonce()
val blindingFactor = generateBlindingFactor()
val blindedMessage = blindMessage(nonce, amount, blindingFactor)

// Reserve owner signs
val blindSignature = signBlindedMessage(blindedMessage, reservePrivateKey)

// Receiver unblinds
val signature = unblindSignature(blindSignature, blindingFactor)
```

### 3. Redeem Anonymously (Onchain)

```scala
// Create redemption transaction
val nullifier = hash(nonce)
val commitment = hash(nonce ++ amount)

val redemptionTx = createRedemptionTx(
  reserveBox = reserveBox,
  nullifier = nullifier,
  commitment = commitment,
  signature = signature,
  amount = amount,
  receiverAddress = anonymousAddress
)
```

## Security Considerations

### Assumptions
- Discrete Logarithm Problem is hard on Secp256k1
- BLAKE2b-256 is collision-resistant
- Schnorr signatures are existentially unforgeable

### Threat Model
- **Protects against:** Honest-but-curious reserve owners, blockchain surveillance
- **Does NOT protect against:** Malicious tracker collusion, compromised keys

### Best Practices
1. Generate truly random blinding factors
2. Use unique nonces for each redemption
3. Rotate keys regularly
4. Use multiple trackers for redundancy

## Limitations

1. **Amount Visibility:** Redemption amounts are visible on-chain
2. **Tracker Transparency:** Tracker sees all debt relationships
3. **Reserve Linkability:** All redemptions from same reserve are linkable

These limitations are explicitly documented and represent acceptable trade-offs for a PoC-level implementation.

## Future Work

- [ ] Amount privacy using Pedersen commitments
- [ ] Range proofs for hidden amounts
- [ ] Multi-tracker support
- [ ] Formal security audit
- [ ] Production-ready implementation

## References

1. Chaum, D. (1983). "Blind signatures for untraceable payments"
2. Basis Protocol: https://www.ergoforum.org/t/basis-a-foundational-on-chain-reserve-approach-to-support-a-variety-of-offchain-protocols/5153
3. ErgoScript Documentation: https://docs.ergoplatform.com/dev/scs/ergoscript/

## Status

**Research PoC** - Not production-ready. Requires security audit before deployment.

## License

Same as parent ChainCash project.

---

**Issue:** #12 - Private Offchain Cash  
**Team:** Dev Engers (LNMIIT Hackathon 2025)
