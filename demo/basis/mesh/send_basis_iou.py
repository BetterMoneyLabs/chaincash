#!/usr/bin/env python3
"""
Send Basis IOU messages over Meshtastic mesh network.

Usage:
    python send_basis_iou.py --payer alice --payee bob --amount 50000000
    python send_basis_iou.py --dest !ba4bf9d0 --payer alice --payee bob --amount 50000000

Requirements:
    pip install meshtastic
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
                       message: str = "Payment", compact: bool = False) -> dict:
    """Create Basis IOU message.
    
    Args:
        payer: Payer node ID
        payee: Payee node ID
        amount: Amount in nanoERG
        message: Payment description
        compact: Use compact format for LoRa constraints
    
    Returns:
        IOU message dictionary
    """
    if compact:
        # Compact format for constrained networks
        return {
            "t": "iou",
            "v": "1.0",
            "p": payer,
            "y": payee,
            "a": amount,
            "c": "nanoERG",
            "m": message,
            "ts": int(time.time() * 1000)
        }
    else:
        # Full format
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


def send_message(interface, dest_id: str, message: dict, timeout: int = 30, 
                 want_ack: bool = True):
    """Send message via Meshtastic interface.
    
    Args:
        interface: Meshtastic interface object
        dest_id: Destination node ID or None for broadcast
        message: Message dictionary
        timeout: Wait time for acknowledgment
        want_ack: Request acknowledgment
    """
    json_str = json.dumps(message, separators=(',', ':'))
    
    print(f"Sending to: {dest_id or 'ALL (broadcast)'}")
    print(f"Payload: {json_str}")
    print(f"Length: {len(json_str)} bytes")
    
    # Warn if message is too long
    if len(json_str) > 180:
        print(f"⚠️  WARNING: Message size may exceed LoRa limits!")
    
    print()
    
    # Send text message
    interface.sendText(json_str, destId=dest_id, wantAck=want_ack)
    
    if want_ack:
        # Wait for acknowledgment
        print(f"Waiting {timeout}s for acknowledgment...")
        time.sleep(timeout)
    
    print("✓ Message sent")


def main():
    parser = argparse.ArgumentParser(
        description='Send Basis IOU over Meshtastic',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  %(prog)s --payer alice --payee bob --amount 50000000
  %(prog)s --dest !ba4bf9d0 --payer alice --payee bob --amount 50000000
  %(prog)s --host 192.168.1.100 --payer alice --payee bob --amount 100000000
  %(prog)s --ble --payer alice --payee bob --amount 50000000
        """
    )
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
    parser.add_argument('--compact', action='store_true', 
                        help='Use compact JSON format for LoRa')
    parser.add_argument('--no-ack', action='store_true', 
                        help='Do not wait for acknowledgment')
    
    args = parser.parse_args()
    
    # Create IOU message
    iou = create_iou_message(
        payer=args.payer,
        payee=args.payee,
        amount=args.amount,
        message=args.message,
        compact=args.compact
    )
    
    # Print summary
    print("=" * 60)
    print("  Basis IOU over Meshtastic")
    print("=" * 60)
    print(f"  Payer:     {args.payer}")
    print(f"  Payee:     {args.payee}")
    print(f"  Amount:    {args.amount / 1e9:.9f} ERG ({args.amount} nanoERG)")
    print(f"  Message:   {args.message}")
    print(f"  Format:    {'Compact' if args.compact else 'Full'}")
    print("=" * 60)
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
        
        print("Connected!")
        print()
        
        # Send message
        send_message(
            interface, 
            args.dest, 
            iou, 
            args.timeout,
            want_ack=not args.no_ack
        )
        
        # Close connection
        interface.close()
        
        print()
        print("Next steps:")
        print("  1. Wait for tracker signature")
        print("  2. Verify IOU on receiver device")
        print("  3. Record in local ledger")
        
    except KeyboardInterrupt:
        print("\nInterrupted by user")
        sys.exit(0)
    except Exception as e:
        print(f"Error: {e}")
        print()
        print("Troubleshooting:")
        print("  - Check USB connection: ls -la /dev/ttyUSB*")
        print("  - Check permissions: sudo usermod -a -G dialout $USER")
        print("  - Try different port: --port /dev/ttyACM0")
        sys.exit(1)


if __name__ == '__main__':
    main()
