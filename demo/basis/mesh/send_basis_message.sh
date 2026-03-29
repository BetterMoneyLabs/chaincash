#!/bin/bash
# send_basis_message.sh - Send Basis IOU over Meshtastic
#
# Usage:
#   ./send_basis_message.sh alice bob 50000000 "Goods payment"
#   DEST_NODE=!ba4bf9d0 ./send_basis_message.sh alice bob 50000000
#   MESHTASTIC_PORT=/dev/ttyACM0 ./send_basis_message.sh alice bob 100000000
#
# Environment variables:
#   MESHTASTIC_PORT - Serial port (default: /dev/ttyUSB0)
#   DEST_NODE       - Destination node ID (optional, broadcasts if not set)
#   CH_INDEX        - Channel index (default: 0)

set -e

# Configuration
MESHTASTIC_PORT="${MESHTASTIC_PORT:-/dev/ttyUSB0}"
DEST_NODE="${DEST_NODE:-}"
CH_INDEX="${CH_INDEX:-0}"

# IOU parameters
PAYER="${1:-alice}"
PAYEE="${2:-bob}"
AMOUNT="${3:-50000000}"  # nanoERG (default 0.05 ERG)
MESSAGE="${4:-Payment}"

# Generate timestamp
TIMESTAMP=$(date +%s%3N)

# Create JSON message (compact format for LoRa)
IOU_JSON=$(cat <<EOF
{"t":"iou","v":"1.0","p":"$PAYER","y":"$PAYEE","a":$AMOUNT,"c":"nanoERG","m":"$MESSAGE","ts":$TIMESTAMP}
EOF
)

# Calculate ERG amount
ERG_AMOUNT=$(echo "scale=9; $AMOUNT / 1000000000" | bc)

echo "=================================================="
echo "  Basis IOU over Meshtastic"
echo "=================================================="
echo "  Payer:     $PAYER"
echo "  Payee:     $PAYEE"
echo "  Amount:    $ERG_AMOUNT ERG ($AMOUNT nanoERG)"
echo "  Message:   $MESSAGE"
echo "  Timestamp: $TIMESTAMP"
echo "=================================================="
echo ""
echo "JSON payload:"
echo "  $IOU_JSON"
echo ""
echo "Payload size: ${#IOU_JSON} bytes"
echo ""

# Check message size (Meshtastic limit ~200 bytes)
if [ ${#IOU_JSON} -gt 180 ]; then
  echo "⚠️  WARNING: Message size (${#IOU_JSON} bytes) may exceed LoRa limits!"
  echo "   Consider using shorter message text."
  echo ""
fi

# Build meshtastic command
CMD="meshtastic --port $MESHTASTIC_PORT --ch-index $CH_INDEX --sendtext \"$IOU_JSON\""

if [ -n "$DEST_NODE" ]; then
  CMD="$CMD --dest $DEST_NODE"
  echo "Destination: $DEST_NODE (direct message)"
else
  echo "Destination: ALL (broadcast)"
fi

echo ""
echo "Sending via Meshtastic..."
echo "Command: $CMD"
echo ""

# Send message
if eval $CMD; then
  echo ""
  echo "✓ Message sent successfully!"
  echo ""
  echo "Next steps:"
  echo "  1. Wait for tracker signature"
  echo "  2. Verify IOU on receiver device"
  echo "  3. Record in local ledger"
else
  echo ""
  echo "✗ Failed to send message"
  exit 1
fi
