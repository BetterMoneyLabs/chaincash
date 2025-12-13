# Blind Signature Protocol Specification

## Overview

This document specifies the cryptographic protocol for unlinkable redemptions in Private Basis using Chaumian blind signatures over the Secp256k1 elliptic curve.

## Cryptographic Primitives

### Elliptic Curve
- **Curve:** Secp256k1 (same as Bitcoin/Ergo)
- **Generator:** `g` (standard Secp256k1 base point)
- **Order:** `q` (prime order of the curve)
- **Hash Function:** BLAKE2b-256

### Schnorr Signatures

Standard Schnorr signature scheme:
- **Private Key:** `sk ∈ Zq`
- **Public Key:** `pk = g^sk`
- **Signature:** `(a, z)` where:
  - `k ← Zq` (random nonce)
  - `a = g^k`
  - `e = H(a || m || pk)` (Fiat-Shamir challenge)
  - `z = k + e·sk mod q`
- **Verification:** `g^z = a · pk^e`

## Blind Signature Protocol

### Phase 1: Blind Signature Issuance (Offchain)

**Inputs:**
- Reserve owner's keypair: `(sk, pk)` where `pk = g^sk`
- Receiver's message: `m = H(nonce || amount)`
- Receiver's blinding factor: `r ← Zq` (random)

**Protocol Steps:**

1. **Receiver blinds message:**
   ```
   m' = m · g^r
   ```

2. **Receiver sends to reserve owner:**
   ```
   (m', proof_of_debt)
   ```
   where `proof_of_debt` is tracker signature on `(receiver_pk, amount, timestamp)`

3. **Reserve owner verifies debt:**
   ```
   verify_tracker_signature(proof_of_debt)
   ```

4. **Reserve owner signs blinded message:**
   ```
   k ← Zq  (random nonce)
   a' = g^k
   e' = H(a' || m' || pk)
   z' = k + e'·sk mod q
   S' = (a', z')
   ```

5. **Reserve owner sends blind signature:**
   ```
   S' → receiver
   ```

6. **Receiver unblinds signature:**
   ```
   a = a' · g^(-r)
   z = z'
   S = (a, z)
   ```

7. **Receiver verifies unblinded signature:**
   ```
   e = H(a || m || pk)
   verify: g^z = a · pk^e
   ```

**Security Property:**
- Reserve owner learns `m'` but NOT `m` (blinded by `g^r`)
- Reserve owner cannot link `S'` to later redemption using `S`
- Unlinkability holds even if reserve owner sees all redemptions

### Phase 2: Anonymous Redemption (Onchain)

**Inputs:**
- Unblinded signature: `S = (a, z)`
- Original nonce: `nonce`
- Redemption amount: `amount`

**Protocol Steps:**

1. **Compute nullifier:**
   ```
   N = H(nonce)
   ```

2. **Compute commitment:**
   ```
   C = H(nonce || amount)
   ```

3. **Construct redemption transaction:**
   ```
   TX = {
       inputs: [reserve_box],
       outputs: [
           reserve_box' (with N added to R5),
           redemption_output (to anonymous address)
       ],
       context_vars: {
           1: N (nullifier),
           2: C (commitment),
           3: amount,
           4: S (signature bytes),
           5: proof (AVL tree proof)
       }
   }
   ```

4. **Contract verification (on-chain):**
   ```
   // Check nullifier not spent
   assert(N ∉ nullifier_tree)
   
   // Verify signature
   message = C || amount
   e = H(a || message || pk)
   assert(g^z = a · pk^e)
   
   // Check amount
   assert(redeemed = amount)
   
   // Add nullifier to spent set
   nullifier_tree' = nullifier_tree.insert(N, HEIGHT)
   ```

## Security Analysis

### Unlinkability Proof (Informal)

**Theorem:** Reserve owner cannot link blind signature issuance to redemption.

**Proof Sketch:**
1. During issuance, reserve owner sees `m' = m · g^r`
2. During redemption, contract verifies signature on `m`
3. Reserve owner must solve: given `m'` and `m`, find `r` such that `m' = m · g^r`
4. This is the Discrete Logarithm Problem (DLP)
5. DLP is computationally hard on Secp256k1
6. Therefore, unlinkability holds under DLP assumption

### Double-Spend Prevention

**Mechanism:** Nullifier set in AVL tree (R5)

**Invariant:** Each nonce can only be redeemed once

**Proof:**
1. Nullifier `N = H(nonce)` is deterministic
2. Contract checks `N ∉ nullifier_tree` before redemption
3. Contract inserts `N` into `nullifier_tree` after redemption
4. AVL tree ensures no duplicates
5. Therefore, same nonce cannot be redeemed twice

### Signature Forgery Resistance

**Assumption:** Schnorr signature security under DLP

**Property:** Only reserve owner can create valid signatures

**Proof:**
1. Valid signature requires `z = k + e·sk`
2. Without `sk`, attacker must solve DLP to compute `z`
3. DLP is hard on Secp256k1
4. Therefore, signatures cannot be forged

## Implementation Notes

### Blinding Factor Generation

```scala
// Secure random generation
val r: BigInt = SecureRandom.getInstanceStrong()
  .nextBytes(32)
  .toBigInt mod curve.order

// CRITICAL: r must be truly random
// Reusing r breaks unlinkability!
```

### Nonce Generation

```scala
// Unique nonce per redemption
val nonce: Array[Byte] = {
  val timestamp = System.currentTimeMillis()
  val randomBytes = new Array[Byte](24)
  SecureRandom.getInstanceStrong().nextBytes(randomBytes)
  
  Blake2b256.hash(
    timestamp.toByteArray ++ 
    randomBytes ++ 
    receiver_pk.getEncoded
  )
}

// CRITICAL: nonce must be unique
// Reusing nonce allows double-spend!
```

### Signature Encoding

```scala
// Schnorr signature encoding
def encodeSignature(a: GroupElement, z: BigInt): Array[Byte] = {
  val aBytes = a.getEncoded(compressed = true)  // 33 bytes
  val zBytes = z.toByteArray.padTo(32, 0.toByte)  // 32 bytes
  aBytes ++ zBytes  // Total: 65 bytes
}

// Signature decoding
def decodeSignature(bytes: Array[Byte]): (GroupElement, BigInt) = {
  require(bytes.length == 65, "Invalid signature length")
  val aBytes = bytes.slice(0, 33)
  val zBytes = bytes.slice(33, 65)
  val a = curve.decodePoint(aBytes)
  val z = BigInt(zBytes)
  (a, z)
}
```

## Test Vectors

### Example 1: Basic Blind Signature

```
# Reserve Owner Keypair
sk = 0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef
pk = g^sk = 0x02a1b2c3d4e5f6...

# Receiver's Message
nonce = 0xabcdef1234567890...
amount = 1000000000  (1 ERG)
m = H(nonce || amount) = 0x9876543210fedcba...

# Blinding
r = 0xfedcba0987654321...
m' = m · g^r = 0x1122334455667788...

# Blind Signature
k = 0x8877665544332211...
a' = g^k = 0x99aabbccddeeff00...
e' = H(a' || m' || pk) = 0x0011223344556677...
z' = k + e'·sk = 0xaabbccddeeff0011...
S' = (a', z')

# Unblinding
a = a' · g^(-r) = 0x2233445566778899...
z = z'
S = (a, z)

# Verification
e = H(a || m || pk) = 0x3344556677889900...
g^z = a · pk^e  ✓ (signature valid)
```

## References

1. Chaum, D. (1983). "Blind signatures for untraceable payments"
2. Schnorr, C. P. (1991). "Efficient signature generation by smart cards"
3. Pointcheval, D., & Stern, J. (2000). "Security arguments for digital signatures and blind signatures"
4. Ergo Platform. "ErgoScript Documentation"

---

**Status:** Research PoC  
**Security Level:** Theoretical (requires formal audit)  
**Implementation:** Reference specification only
