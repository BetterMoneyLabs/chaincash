# Sending Basis Messages over Meshtastic

## Overview

This guide shows how to send Basis protocol IOU messages over the **Meshtastic** mesh network using the command-line interface.

Meshtastic is a real-world mesh networking technology using LoRa radios, perfect for offline Basis transactions in disconnected communities.

---

## Prerequisites

### 1. Install Meshtastic CLI

```bash
# Install via pip
pip install meshtastic

# Or via pipx (recommended for isolation)
pipx install meshtastic

# Verify installation
meshtastic --version
```

### 2. Connect Meshtastic Device

Connect your Meshtastic device (Rak4631, T-Beam, Heltec, etc.) via USB:

```bash
# List available devices
meshtastic --info

# Should show device info like:
# Owner: {id: !12345678, long_name: "My Node", short_name: "MN"}
# My info: ...
# Metadata: ...
```

### 3. Configure Device

```bash
# Set device name
meshtastic --set owner.long_name "BasisTracker"

# Set location (optional)
meshtastic --set location.lat 40.7128
meshtastic --set location.lon -74.0060

# Configure for text messaging (default)
meshtastic --set channel.psks[0].key "AQ=="  # Default key
```

---

## Sending Basis Messages

### Message Format

Basis IOU messages are JSON-encoded and sent as text:

```json
{
  "type": "iou_transfer",
  "version": "1.0",
  "payer": "alice_node",
  "payee": "bob_node",
  "amount": 50000000,
  "currency": "nanoERG",
  "message": "Payment for goods",
  "timestamp": 1704000000000,
  "signature": "..."
}
```

### Basic Send Command

```bash
# Send to all nodes (broadcast)
meshtastic --sendtext '{"type":"iou_transfer","amount":50000000}'

# Send to specific node
meshtastic --dest !ba4bf9d0 --sendtext '{"type":"iou_transfer","amount":50000000}'

# Send via specific port
meshtastic --port /dev/ttyUSB0 --sendtext '{"type":"iou_transfer","amount":50000000}'
```

### Complete Example with Acknowledgment

```bash
# Send IOU and wait for acknowledgment
meshtastic \
  --port /dev/ttyUSB0 \
  --dest !ba4bf9d0 \
  --sendtext '{"type":"iou_transfer","payer":"alice","payee":"bob","amount":50000000}' \
  --ack \
  --timeout 60
```

---

## Helper Script: send_basis_message.sh

```bash
#!/bin/bash
# send_basis_message.sh - Send Basis IOU over Meshtastic

set -e

# Configuration
MESHTASTIC_PORT="${MESHTASTIC_PORT:-/dev/ttyUSB0}"
DEST_NODE="${DEST_NODE:-}"  # Optional: !ba4bf9d0
CH_INDEX="${CH_INDEX:-0}"

# IOU parameters
PAYER="${1:-alice}"
PAYEE="${2:-bob}"
AMOUNT="${3:-50000000}"  # nanoERG (default 0.05 ERG)
MESSAGE="${4:-Payment}"

# Generate timestamp
TIMESTAMP=$(date +%s%3N)

# Create JSON message
IOU_JSON=$(cat <<EOF
{
  "type": "iou_transfer",
  "version": "1.0",
  "payer": "$PAYER",
  "payee": "$PAYEE",
  "amount": $AMOUNT,
  "currency": "nanoERG",
  "message": "$MESSAGE",
  "timestamp": $TIMESTAMP
}
EOF
)

echo "=== Basis IOU over Meshtastic ==="
echo "Payer: $PAYER"
echo "Payee: $PAYEE"
echo "Amount: $(echo "scale=9; $AMOUNT / 1000000000" | bc) ERG"
echo "Message: $MESSAGE"
echo ""
echo "JSON payload:"
echo "$IOU_JSON"
echo ""

# Build meshtastic command
CMD="meshtastic --port $MESHTASTIC_PORT --ch-index $CH_INDEX --sendtext '$IOU_JSON'"

if [ -n "$DEST_NODE" ]; then
  CMD="$CMD --dest $DEST_NODE"
  echo "Destination: $DEST_NODE"
fi

echo ""
echo "Sending via Meshtastic..."
echo "Command: $CMD"
echo ""

# Send message
eval $CMD

echo ""
echo "✓ Message sent successfully!"
```

### Usage

```bash
# Make executable
chmod +x send_basis_message.sh

# Send IOU (broadcast)
./send_basis_message.sh alice bob 50000000 "Goods payment"

# Send to specific node
DEST_NODE=!ba4bf9d0 ./send_basis_message.sh alice bob 50000000 "Goods payment"

# Custom port
MESHTASTIC_PORT=/dev/ttyACM0 ./send_basis_message.sh alice bob 100000000 "Service payment"
```

---

## Python Script: send_basis_iou.py

```python
#!/usr/bin/env python3
"""
Send Basis IOU messages over Meshtastic mesh network.

Usage:
    python send_basis_iou.py --payer alice --payee bob --amount 50000000
    python send_basis_iou.py --dest !ba4bf9d0 --payer alice --payee bob --amount 50000000
"""

import argparse
import json
import time
import sys
from datetime import datetime

try:
    from meshtastic.serial_interface import SerialInterface
    from meshtastic.tcp_interface import TCPInterface
    from meshtastic.ble_interface import BLEInterface
except ImportError:
    print("Error: meshtastic package not installed")
    print("Install with: pip install meshtastic")
    sys.exit(1)


def create_iou_message(payer: str, payee: str, amount: int, 
                       message: str = "Payment") -> dict:
    """Create Basis IOU message."""
    return {
        "type": "iou_transfer",
        "version": "1.0",
        "payer": payer,
        "payee": payee,
        "amount": amount,
        "currency": "nanoERG",
        "message": message,
        "timestamp": int(time.time() * 1000),
        "datetime": datetime.now().isoformat()
    }


def send_message(interface, dest_id: str, message: dict, timeout: int = 30):
    """Send message via Meshtastic interface."""
    json_str = json.dumps(message, separators=(',', ':'))
    
    print(f"Sending to: {dest_id or 'ALL'}")
    print(f"Payload: {json_str}")
    print(f"Length: {len(json_str)} bytes")
    print()
    
    # Send text message
    interface.sendText(json_str, destId=dest_id, wantAck=True)
    
    # Wait for acknowledgment
    print(f"Waiting {timeout}s for acknowledgment...")
    time.sleep(timeout)
    
    print("✓ Message sent")


def main():
    parser = argparse.ArgumentParser(description='Send Basis IOU over Meshtastic')
    parser.add_argument('--payer', required=True, help='Payer node ID')
    parser.add_argument('--payee', required=True, help='Payee node ID')
    parser.add_argument('--amount', type=int, default=50000000, 
                        help='Amount in nanoERG (default: 50000000 = 0.05 ERG)')
    parser.add_argument('--message', default='Payment', help='Payment message')
    parser.add_argument('--dest', help='Destination node ID (e.g., !ba4bf9d0)')
    parser.add_argument('--port', default='/dev/ttyUSB0', help='Serial port')
    parser.add_argument('--host', help='TCP host (alternative to serial)')
    parser.add_argument('--ble', action='store_true', help='Use BLE connection')
    parser.add_argument('--timeout', type=int, default=30, help='Ack timeout (seconds)')
    
    args = parser.parse_args()
    
    # Create IOU message
    iou = create_iou_message(
        payer=args.payer,
        payee=args.payee,
        amount=args.amount,
        message=args.message
    )
    
    # Print summary
    print("=" * 50)
    print("Basis IOU over Meshtastic")
    print("=" * 50)
    print(f"Payer:   {args.payer}")
    print(f"Payee:   {args.payee}")
    print(f"Amount:  {args.amount / 1e9:.9f} ERG")
    print(f"Message: {args.message}")
    print("=" * 50)
    print()
    
    # Connect to device
    try:
        if args.ble:
            print(f"Connecting via BLE...")
            interface = BLEInterface()
        elif args.host:
            print(f"Connecting to TCP host {args.host}...")
            interface = TCPInterface(args.host)
        else:
            print(f"Connecting to serial port {args.port}...")
            interface = SerialInterface(args.port)
        
        # Send message
        send_message(interface, args.dest, iou, args.timeout)
        
        # Close connection
        interface.close()
        
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)


if __name__ == '__main__':
    main()
```

### Usage

```bash
# Install dependencies
pip install meshtastic

# Make executable
chmod +x send_basis_iou.py

# Basic usage (broadcast)
python send_basis_iou.py --payer alice --payee bob --amount 50000000

# Send to specific node
python send_basis_iou.py \
  --dest !ba4bf9d0 \
  --payer alice \
  --payee bob \
  --amount 50000000 \
  --message "Goods payment"

# Via TCP
python send_basis_iou.py \
  --host 192.168.1.100 \
  --payer alice \
  --payee bob \
  --amount 100000000

# Via BLE
python send_basis_iou.py \
  --ble \
  --payer alice \
  --payee bob \
  --amount 50000000
```

---

## Receiving Messages

### Listen for Messages

```bash
# Listen for all messages
meshtastic --port /dev/ttyUSB0

# Filter for Basis messages
meshtastic --port /dev/ttyUSB0 | grep '"type":"iou_transfer"'
```

### Python Receiver Script

```python
#!/usr/bin/env python3
"""Listen for Basis IOU messages over Meshtastic."""

import json
import sys
from meshtastic.serial_interface import SerialInterface
from pubsub import pub

def on_receive(packet, interface):
    """Callback for received packets."""
    if 'decoded' in packet and 'payload' in packet['decoded']:
        payload = packet['decoded']['payload']
        
        # Try to decode as text
        try:
            text = payload.decode('utf-8')
            data = json.loads(text)
            
            # Check if it's a Basis IOU message
            if data.get('type') == 'iou_transfer':
                print("=" * 50)
                print("📬 Received Basis IOU!")
                print("=" * 50)
                print(f"From:    {packet.get('fromId', 'unknown')}")
                print(f"Payer:   {data.get('payer')}")
                print(f"Payee:   {data.get('payee')}")
                print(f"Amount:  {data.get('amount', 0) / 1e9:.9f} ERG")
                print(f"Message: {data.get('message')}")
                print(f"Time:    {data.get('datetime', 'unknown')}")
                print("=" * 50)
                
        except (json.JSONDecodeError, UnicodeDecodeError):
            pass  # Not a JSON message

# Subscribe to receive events
pub.subscribe(on_receive, 'meshtastic.receive')

# Connect to device
print("Listening for Basis IOU messages...")
print("Press Ctrl+C to exit")
print()

try:
    interface = SerialInterface()
    while True:
        time.sleep(1)
except KeyboardInterrupt:
    print("\nExiting...")
    interface.close()
```

---

## Message Size Limits

Meshtastic has message size constraints:

| Parameter | Limit |
|-----------|-------|
| Max text message | ~200 bytes (varies by firmware) |
| Recommended | < 150 bytes for reliability |

### Compact IOU Format

For constrained networks, use compact format:

```json
{"t":"iou","p":"alice","y":"bob","a":50000000,"m":"payment","ts":1704000000000}
```

**Field abbreviations:**
- `t`: type
- `p`: payer
- `y`: payee
- `a`: amount
- `m`: message
- `ts`: timestamp

---

## Security Considerations

### 1. Encryption

Meshtastic supports channel encryption:

```bash
# Set encryption key (base64 encoded)
meshtastic --set channel.psks[0].key "YOUR_KEY_HERE"

# Generate secure key
openssl rand -base64 32
```

### 2. Signature Verification

Always verify IOU signatures:

```bash
# Message includes signature field
{"type":"iou_transfer",...,"signature":"..."}

# Verify with Basis verification tool
python verify_iou_signature.py --message iou.json
```

### 3. Node Authentication

Verify sender node ID:

```python
def verify_sender(packet, expected_sender):
    """Verify packet is from expected sender."""
    return packet.get('fromId') == expected_sender
```

---

## Testing

### Loopback Test

```bash
# Send message to self
NODE_ID=$(meshtastic --info | grep '"id"' | head -1 | cut -d'"' -f4)
meshtastic --dest $NODE_ID --sendtext '{"type":"test"}'
```

### Range Test

```bash
# Node A (sender)
meshtastic --dest !NODE_B --sendtext "test 1" --ack

# Node B (receiver, in different location)
meshtastic --sendtext "ack 1" --dest !NODE_A --ack
```

---

## Troubleshooting

### Device Not Found

```bash
# Check USB connection
ls -la /dev/ttyUSB*

# Check permissions
sudo usermod -a -G dialout $USER
# Log out and back in
```

### Message Not Sending

```bash
# Check device status
meshtastic --info

# Check channel configuration
meshtastic --ch-index 0 --ch-info

# Reset device if needed
meshtastic --reset
```

### Message Too Long

```bash
# Check message length
echo -n '{"type":"iou_transfer",...}' | wc -c

# Use compact format if > 150 bytes
```

---

## Resources

- [Meshtastic Documentation](https://meshtastic.org/)
- [Meshtastic CLI Reference](https://meshtastic.org/docs/software/python/cli/usage/)
- [Meshtastic GitHub](https://github.com/meshtastic/Meshtastic-python)
- [Basis Protocol](../../README.md)

---

**Next Steps:**
1. Set up Meshtastic devices in your community
2. Test IOU messaging with the scripts above
3. Integrate with Basis wallet app
4. Deploy in disconnected areas
