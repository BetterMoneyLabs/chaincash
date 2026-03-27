# Basis Simple Demo

Simple demonstration of the Basis reserve protocol for note creation and redemption.

## Overview

Basis enables offchain debt tracking with onchain redemption. This demo shows:
1. Reserve deployment with collateral
2. Note creation with tracker signature
3. Note redemption with AVL proofs

## Files

### Demo Data
- `note.json` - IOU note created by BasisNoteCreator
- `sign_request.json` - Unsigned redemption transaction
- `new_reserve_deployment.json` - Reserve deployment transaction
- `tracker_box_setup.json` - Tracker box configuration

### Scripts
- `redeem.sh` - Full redemption script
- `debug_signing.sh` - Debug signing issues

### Source Code
- `src/BasisNoteRedeemer.scala` - Redemption logic
- `src/BasisDeployer.scala` - Reserve deployment
- `src/BasisNoteCreator.scala` - Note creation
- `src/TrackerBoxSetup.scala` - Tracker configuration

## Quick Start

### Prerequisites

1. Ergo node running on `http://127.0.0.1:9053`
2. Wallet unlocked with Bob's secret key
3. Reserve box created (scan 38)
4. Tracker box created (scan 36)

### 1. Find Fee Boxes

```bash
# Find 4 boxes with 250000 nanoERG each (no assets)
FEE_BOXES=$(curl -s "http://127.0.0.1:9053/wallet/boxes/unspent" | \
  python3 -c "import json,sys; boxes=[b['box']['boxId'] for b in json.load(sys.stdin) if b['box']['value']==250000 and not b['box']['assets']][:4]; print(','.join(boxes))")

echo "Fee boxes: $FEE_BOXES"
```

### 2. Generate Redemption Transaction

```bash
cd demo/basis/simple

sbt "runMain chaincash.contracts.BasisNoteRedeemer \
  --note-json note.json \
  --reserve-box auto \
  --tracker-box auto \
  --fee-box $FEE_BOXES \
  --output sign_request.json"
```

### 3. Sign Transaction

```bash
curl -X POST "http://127.0.0.1:9053/wallet/transaction/sign" \
  -H "api_key: hello" \
  -H "Content-Type: application/json" \
  -d @sign_request.json
```

### 4. Debug Signing Issues

```bash
./debug_signing.sh
```

## Transaction Structure

```
Inputs (5):
  1. Reserve box (100000000 nanoERG) [with contract extension]
  2-5. Fee boxes (4 × 250000 = 1000000 nanoERG) [with empty extension]

Data Inputs (1):
  1. Tracker box

Outputs (3):
  1. Reserve: 50000000 nanoERG
  2. Receiver (Bob): 50000000 nanoERG
  3. Fee Recipient: 1000000 nanoERG

Balance: 101000000 (in) - 101000000 (out) = 0 ✓
```

**Note:** Ergo transactions don't have a "fee" field. The fee is the difference between inputs and outputs, paid to the fee recipient output.

## Testing

Run the demo test suite:

```bash
sbt "testOnly chaincash.demo.BasisDemoSpec"
```

Tests verify:
- Reserve box AVL tree format
- Tracker box AVL tree format
- Reserve insert proof generation
- Tracker lookup proof generation
- Signature encoding (65 bytes: 33 + 32)
- Transaction structure

## Key Parameters

| Parameter | Value |
|-----------|-------|
| Reserve Token ID | `21426942b8d30a7a293f04f44caa2febc536c33121f03f5259ad7be59015b972` |
| Tracker NFT ID | `8b1ab583bb085ecbd8fa9bc2fd59784afcdfce5496eb146bb3dd04664b56822a` |
| Reserve Scan ID | 38 |
| Tracker Scan ID | 36 |
| Fee | 1000000 nanoERG (4 × 250000) |

## Fee Recipient

The fee is paid to this address:

```
ergoTree: 1005040004000e36100204a00b08cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798ea02d192a39a8cc7a701730073011001020402d19683030193a38cc7b2a57300000193c2b2a57301007473027303830108cdeeac93b1a57304
address:  2iHkR7CWvD1R4j1yZg5bkeDRQavjAaVPeTDFGGLZduHyfWMuYpmhHocX8GJoaieTx78FntzJbCBVL6rf96ocJoZdmWBL2fci7NqWgAirppPQmZ7fN9V6z13Ay6brPriBKYqLp1bT2Fk4FkFLCfdPpe
```

## Common Issues

### "Script reduced to false"

This error means one of the contract conditions failed. Check:
1. Signatures are encoded correctly (65 bytes with 32-byte z)
2. AVL proofs are valid for the tree state
3. Tracker NFT matches reserve R6
4. Debt amount matches tracker AVL tree

### "Malformed request: Attempt to decode value on failed cursor"

JSON formatting issue. Ensure:
1. All inputs have `extension` field (even if empty: `{}`)
2. JSON is properly formatted (no trailing commas)
3. Use `python3 -c "import json; json.dump(json.load(open('sign_request.json')), open('sign_request.json','w'), separators=(',',':'))"` to clean

### Reserve box not found

Make sure:
1. Reserve is created and scanned with scanId=38
2. Reserve and Tracker boxes are unspent

## References

- [Basis Whitepaper](../../docs/conf/conf.pdf)
- [Ergo Documentation](https://docs.ergoplatform.com/)
- [Sigmastate Documentation](https://github.com/sigmastate/sigmastate-interpreter)
