#!/bin/bash

# Debug script to check why signing might fail

API_KEY="${ERGO_API_KEY:-hello}"
NODE_URL="${ERGO_NODE_URL:-http://127.0.0.1:9053}"

echo "=== Ergo Node Connection Test ==="
echo "Node URL: $NODE_URL"
echo ""

# Check node is running
echo "1. Checking node connection..."
NODE_INFO=$(curl -s "$NODE_URL/info" 2>/dev/null)
if [ $? -eq 0 ] && [ -n "$NODE_INFO" ]; then
    echo "✓ Node is running"
    echo "   State: $(echo "$NODE_INFO" | python3 -c "import sys,json; print(json.load(sys.stdin).get('state', 'unknown'))" 2>/dev/null || echo "unknown")"
else
    echo "✗ Cannot connect to node"
    exit 1
fi
echo ""

# Check wallet is unlocked
echo "2. Checking wallet status..."
WALLET_STATUS=$(curl -s "$NODE_URL/wallet/status" -H "api_key: $API_KEY" 2>/dev/null)
if echo "$WALLET_STATUS" | grep -q "error"; then
    echo "✗ Wallet error: $WALLET_STATUS"
    echo "   Make sure wallet is unlocked: curl -X POST '$NODE_URL/wallet/unlock' -H 'api_key: $API_KEY' -d '{\"pass\": \"your_password\"}'"
else
    echo "✓ Wallet is accessible"
    HEIGHT=$(echo "$WALLET_STATUS" | python3 -c "import sys,json; print(json.load(sys.stdin).get('fullHeight', 'unknown'))" 2>/dev/null || echo "unknown")
    echo "   Wallet height: $HEIGHT"
fi
echo ""

# Check if reserve box exists
echo "3. Checking reserve box..."
RESERVE_BOX_ID="0b494c598ffd46c72ea95c72e5d47a8cb136eab1dc12da636dc4f817f97bfdb2"
RESERVE_BOX=$(curl -s "$NODE_URL/utxo/byId/$RESERVE_BOX_ID" 2>/dev/null)
if echo "$RESERVE_BOX" | grep -q "error\|null"; then
    echo "✗ Reserve box NOT FOUND in UTXO set"
    echo "   Box ID: $RESERVE_BOX_ID"
    echo "   The box must exist in the node's UTXO set for signing"
else
    echo "✓ Reserve box found"
    BOX_VALUE=$(echo "$RESERVE_BOX" | python3 -c "import sys,json; print(json.load(sys.stdin).get('value', 'unknown'))" 2>/dev/null || echo "unknown")
    echo "   Value: $BOX_VALUE nanoERG"
fi
echo ""

# Check if tracker box exists
echo "4. Checking tracker box (data input)..."
TRACKER_BOX_ID="49787748507c2c2a2e416c3c4d5ad41ee9d448e9966a709e313279ac2c58e431"
TRACKER_BOX=$(curl -s "$NODE_URL/utxo/byId/$TRACKER_BOX_ID" 2>/dev/null)
if echo "$TRACKER_BOX" | grep -q "error\|null"; then
    echo "✗ Tracker box NOT FOUND in UTXO set"
    echo "   Box ID: $TRACKER_BOX_ID"
    echo "   The tracker box must exist for the data input"
else
    echo "✓ Tracker box found"
fi
echo ""

# Check if wallet has Alice's secret key
echo "5. Checking if wallet has Alice's secret key..."
ALICE_ADDRESS="9hNQcqi72NB5u5Tw6tbfCGbEKByguR7njvcyZXnXPLvV3Do1DiJ"
ALICE_BALANCE=$(curl -s "$NODE_URL/wallet/balance/$ALICE_ADDRESS" -H "api_key: $API_KEY" 2>/dev/null)
if echo "$ALICE_BALANCE" | grep -q "error"; then
    echo "✗ Address not in wallet: $ALICE_ADDRESS"
    echo "   Import Alice's secret key first:"
    echo "   curl -X POST '$NODE_URL/wallet/update' -H 'api_key: $API_KEY' -H 'Content-Type: application/json' -d '{\"secret\": \"c693d626538e9dd926519c13f3855412d60aaaa9c8818e7725415a45e92f3108\"}'"
else
    echo "✓ Alice's address is in wallet"
    echo "   Balance: $(echo "$ALICE_BALANCE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('balance', 'unknown'))" 2>/dev/null || echo "unknown") nanoERG"
fi
echo ""

# Try to sign and capture detailed error
echo "6. Attempting to sign (capturing detailed error)..."
SIGN_RESULT=$(curl -s -X POST "$NODE_URL/wallet/transaction/sign" \
  -H "accept: application/json" \
  -H "api_key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d @sign_request.json 2>&1)

if echo "$SIGN_RESULT" | grep -q "None.get"; then
    echo "✗ Signing failed with 'None.get' error"
    echo ""
    echo "Common causes:"
    echo "  1. Reserve box not in UTXO set (checked in step 3)"
    echo "  2. Tracker box not in UTXO set (checked in step 4)"
    echo "  3. Wallet doesn't have secret key for reserve owner (checked in step 5)"
    echo "  4. Box is already spent"
    echo ""
    echo "Full error response:"
    echo "$SIGN_RESULT" | python3 -m json.tool 2>/dev/null || echo "$SIGN_RESULT"
elif echo "$SIGN_RESULT" | grep -q "error"; then
    echo "✗ Signing failed with error:"
    echo "$SIGN_RESULT" | python3 -m json.tool 2>/dev/null || echo "$SIGN_RESULT"
else
    echo "✓ Signing successful!"
    TX_ID=$(echo "$SIGN_RESULT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id', 'unknown'))" 2>/dev/null || echo "unknown")
    echo "Transaction ID: $TX_ID"
fi
echo ""

echo "=== Debug Complete ==="
