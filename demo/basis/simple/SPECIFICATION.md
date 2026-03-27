# Basis Simple Demo - Technical Specification

**Version:** 1.0  
**Date:** March 27, 2026  
**Status:** Production Ready

---

## 1. Overview

This document specifies the technical implementation of the Basis Simple Demo - a minimal working example of the Basis reserve protocol for peer-to-peer money creation via trust and collateral.

### 1.1 Purpose

The demo demonstrates:
1. **Reserve Deployment** - Creating an ERG-backed reserve
2. **Note Creation** - Issuing IOU notes with tracker signature
3. **Note Redemption** - Redeeming notes against reserve collateral

### 1.2 Scope

This specification covers:
- Transaction structure and format
- AVL tree operations
- Signature generation and verification
- Fee handling
- Contract conditions

---

## 2. System Architecture

### 2.1 Components

```
┌─────────────────────────────────────────────────────────────┐
│                    Basis Demo Components                     │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────────────┐      ┌──────────────────┐            │
│  │ BasisDeployer    │      │ BasisNoteCreator │            │
│  │                  │      │                  │            │
│  │ - Deploy reserve │      │ - Create IOU     │            │
│  │ - Set AVL tree   │      │ - Get signatures │            │
│  │ - Configure NFT  │      │ - Track debt     │            │
│  └──────────────────┘      └──────────────────┘            │
│                                                              │
│  ┌──────────────────┐      ┌──────────────────┐            │
│  │ BasisNoteRedeemer│      │ Ergo Node        │            │
│  │                  │      │                  │            │
│  │ - Verify note    │      │ - Sign tx        │            │
│  │ - Generate AVL   │      │ - Broadcast      │            │
│  │ - Build tx       │      │ - Validate       │            │
│  └──────────────────┘      └──────────────────┘            │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Data Flow

```
Note Creation:
  Alice → BasisNoteCreator → Tracker → Signed Note → Bob

Note Redemption:
  Bob → BasisNoteRedeemer → Reserve Contract → ERG to Bob
```

---

## 3. Transaction Specification

### 3.1 Input Structure

**Reserve Input (1 box):**
```json
{
  "boxId": "<reserve_box_id>",
  "extension": {
    "0": "0200",                    // action=0 (REDEEM), index=0
    "1": "<receiver_pubkey>",       // GroupElement
    "2": "<reserve_signature>",     // Coll[Byte] (65 bytes)
    "3": "<total_debt>",            // Long (50000000)
    "5": "<reserve_insert_proof>",  // Coll[Byte] (70 bytes)
    "6": "<tracker_signature>",     // Coll[Byte] (65 bytes)
    "8": "<tracker_lookup_proof>"   // Coll[Byte] (113 bytes)
  }
}
```

**Fee Inputs (4 boxes):**
```json
{
  "boxId": "<fee_box_id>",
  "extension": {}  // Empty extension required for signing
}
```

**Data Input (1 box):**
```json
{
  "boxId": "<tracker_box_id>"  // No extension needed
}
```

### 3.2 Output Structure

**Reserve Output:**
```json
{
  "ergoTree": "<basis_contract_hex>",
  "creationHeight": <current_height>,
  "value": 50000000,
  "assets": [{"tokenId": "<reserve_nft>", "amount": 1}],
  "additionalRegisters": {
    "R4": "<owner_pubkey>",
    "R5": "<updated_avl_tree>",  // Tree after insert
    "R6": "0e20<tracker_nft_id>"
  }
}
```

**Receiver Output:**
```json
{
  "ergoTree": "0008cd<receiver_pubkey>",  // P2PK address
  "creationHeight": <current_height>,
  "value": 50000000,
  "assets": [],
  "additionalRegisters": {}
}
```

**Fee Recipient Output:**
```json
{
  "ergoTree": "1005040004000e36100204a00b08cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798ea02d192a39a8cc7a701730073011001020402d19683030193a38cc7b2a57300000193c2b2a57301007473027303830108cdeeac93b1a57304",
  "creationHeight": <current_height>,
  "value": 1000000,
  "assets": [],
  "additionalRegisters": {}
}
```

### 3.3 Balance Calculation

```
Inputs:
  Reserve box:     100000000 nanoERG
  Fee boxes (4x):    1000000 nanoERG (4 × 250000)
                   -----------
  Total input:     101000000 nanoERG

Outputs:
  Reserve output:   50000000 nanoERG
  Receiver output:  50000000 nanoERG
  Fee recipient:     1000000 nanoERG
                   -----------
  Total output:    101000000 nanoERG

Balance: 101000000 - 101000000 = 0 ✓
```

**Note:** Ergo unsigned transactions don't have a "fee" field. The fee is implicit as `inputs - outputs`.

---

## 4. AVL Tree Specification

### 4.1 Tree Parameters

| Parameter | Value |
|-----------|-------|
| Key Length | 32 bytes (Blake2b256 hash) |
| Value Length | Variable (8 bytes for Long) |
| Flags | InsertOnly (0x01) |
| Plasma Parameters | (32, None) |

### 4.2 Key Construction

```scala
val key = Blake2b256(ownerKeyBytes ++ receiverKeyBytes)
// Example: 6995ccf33c8a09705612e6ee3808bb4cedb48cb7b7c019ecdc68b74e7ed912a4
```

### 4.3 Value Encoding

```scala
val value = Longs.toByteArray(amount)
// Example: 0000000002faf080 (50000000 in big-endian)
```

### 4.4 Tree Serialization Format

```
Byte 0:      Type tag (0x64 for AvlTree)
Bytes 1-33:  Digest (32-byte hash + 1-byte height)
Byte 34:     Flags
Bytes 35-38: Key length (4 bytes, big-endian)
Bytes 39-42: Value length option (4 bytes if present)
```

**Example (Empty Tree):**
```
64 4ec61f485b98eb87153f7c57db4f5ecd75556fddbc403b41acf8441fde8e1609 00 01 20 00
│  ││                                                        │  │  │  │  │
│  └─ Digest (33 bytes)                                      │  │  │  │  └─ Value length (None)
│                                                            │  │  │  └──── Key length (32)
│                                                            │  │  └─────── Flags (0x01 = InsertOnly)
└─ Type (0x64)                                               └─ Height (0)
```

### 4.5 Proof Generation

**Reserve Insert Proof (Context Var 5):**
```scala
val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](InsertOnly, chainCashPlasmaParameters)
val insertResult = plasmaMap.insert((key, value))
val proof = insertResult.proof.bytes  // 70 bytes for empty→single insert
```

**Tracker Lookup Proof (Context Var 8):**
```scala
val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](InsertOnly, chainCashPlasmaParameters)
plasmaMap.insert((key, value))
val lookupResult = plasmaMap.lookUp(key)
val proof = lookupResult.proof.bytes  // 113 bytes
```

---

## 5. Signature Specification

### 5.1 Message Construction

```scala
val key = Blake2b256(ownerKeyBytes ++ receiverKeyBytes)
val message = key ++ Longs.toByteArray(totalDebt)
// Total: 40 bytes (32 + 8)
```

**Example:**
```
6995ccf33c8a09705612e6ee3808bb4cedb48cb7b7c019ecdc68b74e7ed912a4 0000000002faf080
││                                                               ││
└─ Key (Blake2b256 of owner||receiver)                           └─ Debt (50000000)
```

### 5.2 Signature Generation

```scala
val (a, z) = SigUtils.sign(message, secretKey)
// a: GroupElement (33 bytes compressed)
// z: BigInt (≤ 255 bits)
```

### 5.3 Signature Encoding

**CRITICAL:** Use BouncyCastle for fixed-width encoding:

```scala
// CORRECT:
val zBytes = BigIntegers.asUnsignedByteArray(32, z.bigInteger)
val sigBytes = GroupElementSerializer.toBytes(a) ++ zBytes
// Total: 65 bytes (33 + 32)

// WRONG (causes verification failure):
val sigBytes = GroupElementSerializer.toBytes(a) ++ z.toByteArray
// May be 66 bytes if z has sign byte!
```

### 5.4 Signature Verification

```scala
val e = Blake2b256(a.getEncoded.toArray ++ message ++ pk.getEncoded.toArray)
val eBigInt = BigInt(e)
val lhs = g.exp(z.bigInteger)
val rhs = a.multiply(pk.exp(eBigInt.bigInteger))
lhs == rhs  // true if valid
```

---

## 6. Contract Conditions

The basis.es contract checks:

```scala
sigmaProp(
  selfPreserved &&           // Output contract unchanged
  trackerIdCorrect &&        // Tracker NFT matches R6
  trackerDebtCorrect &&      // Debt exists in tracker tree
  properRedemptionTree &&    // AVL tree updated correctly
  properReserveSignature &&  // Reserve owner signed
  properlyRedeemed &&        // Amount <= (totalDebt - redeemedDebt)
  receiverCondition          // Receiver pubkey verified
)
```

### 6.1 Condition Details

**selfPreserved:**
- Output proposition bytes == input proposition bytes
- Output tokens == input tokens
- Output R4 == input R4
- Output R6 == input R6

**trackerIdCorrect:**
- `tracker.tokens(0)._1 == SELF.R6[Coll[Byte]].get`

**trackerDebtCorrect:**
- `trackerTree.get(key, proof).get == totalDebt`

**properRedemptionTree:**
- `SELF.R5.insert((key, value), proof) == selfOut.R5`

**properReserveSignature:**
- Schnorr signature verification with message = `key || totalDebt`

**properlyRedeemed:**
- `redeemed > 0`
- `redeemed <= (totalDebt - redeemedDebt)`
- Tracker signature valid

**receiverCondition:**
- `proveDlog(receiver)`

---

## 7. Fee Handling

### 7.1 Fee Box Requirements

- **Value:** 250000 nanoERG each
- **Quantity:** 4 boxes
- **Assets:** Empty array `[]`
- **Extension:** Empty `{}` (required for signing)

### 7.2 Finding Fee Boxes

```bash
curl "http://localhost:9053/wallet/boxes/unspent" | \
  python3 -c "import json,sys; [print(b['box']['boxId']) for b in json.load(sys.stdin) if b['box']['value']==250000 and not b['box']['assets']]" | head -4 | tr '\n' ',' | sed 's/,$//'
```

### 7.3 Fee Recipient

The fee is paid to a hardcoded address:

```
ergoTree: 1005040004000e36100204a00b08cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798ea02d192a39a8cc7a701730073011001020402d19683030193a38cc7b2a57300000193c2b2a57301007473027303830108cdeeac93b1a57304
address:  2iHkR7CWvD1R4j1yZg5bkeDRQavjAaVPeTDFGGLZduHyfWMuYpmhHocX8GJoaieTx78FntzJbCBVL6rf96ocJoZdmWBL2fci7NqWgAirppPQmZ7fN9V6z13Ay6brPriBKYqLp1bT2Fk4FkFLCfdPpe
```

---

## 8. Command Line Interface

### 8.1 BasisNoteRedeemer

```bash
sbt "runMain chaincash.contracts.BasisNoteRedeemer \
  --note-json <file> \
  --reserve-box <id|auto> \
  --tracker-box <id|auto> \
  --fee-box <box1,box2,box3,box4> \
  --output <file> \
  --reserve-owner-secret <hex> \
  --tracker-secret <hex>"
```

**Parameters:**
| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `--note-json` | Yes | - | IOU note JSON file |
| `--reserve-box` | No | auto | Reserve box ID or 'auto' for scan API |
| `--tracker-box` | No | auto | Tracker box ID or 'auto' for scan API |
| `--fee-box` | No | - | Comma-separated fee box IDs |
| `--output` | No | stdout | Output file |
| `--reserve-owner-secret` | No | Alice | Reserve owner secret (hex) |
| `--tracker-secret` | No | Tracker | Tracker secret (hex) |

### 8.2 Scan Configuration

| Scan ID | Purpose | Box Type |
|---------|---------|----------|
| 38 | Reserve monitoring | Basis reserve contract |
| 36 | Tracker monitoring | Tracker contract |

---

## 9. Testing

### 9.1 Running Tests

```bash
# Run demo test suite
sbt "testOnly chaincash.demo.BasisDemoSpec"

# Run all Basis tests
sbt "testOnly chaincash.Basis*"
```

### 9.2 Test Coverage

| Test | Purpose | Status |
|------|---------|--------|
| Reserve box AVL tree format | Verify tree serialization | ✓ |
| Tracker box AVL tree format | Verify tree serialization | ✓ |
| Reserve insert proof | Generate proof for empty→single | ✓ |
| Tracker lookup proof | Generate proof for key lookup | ✓ |
| Signature encoding | Verify 65-byte format | ✓ |
| Transaction structure | Verify inputs/outputs/fee | ✓ |

---

## 10. Error Handling

### 10.1 Common Errors

**"Script reduced to false":**
- Cause: Contract condition failed
- Solution: Check signatures, AVL proofs, debt amounts

**"Malformed request: Attempt to decode value on failed cursor":**
- Cause: Invalid JSON format
- Solution: Ensure all inputs have `extension` field

**"Reserve box not found":**
- Cause: Box doesn't exist or wrong scan ID
- Solution: Verify reserve is created and scanned with scanId=38

### 10.2 Debugging

```bash
# Run debug script
./debug_signing.sh

# Check node connection
curl "http://localhost:9053/info"

# Check wallet status
curl "http://localhost:9053/wallet/status" -H "api_key: hello"

# Check reserve box
curl "http://localhost:9053/utxo/byId/<reserve_box_id>"
```

---

## 11. References

- [Basis Whitepaper](../../docs/conf/conf.pdf)
- [Ergo Documentation](https://docs.ergoplatform.com/)
- [Sigmastate Documentation](https://github.com/sigmastate/sigmastate-interpreter)
- [Scrypto AVL Trees](../../trees/README.md)

---

*Last updated: March 27, 2026*
