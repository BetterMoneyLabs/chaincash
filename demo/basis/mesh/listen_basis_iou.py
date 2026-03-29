#!/usr/bin/env python3
"""
Listen for Basis IOU messages over Meshtastic.

Usage:
    python listen_basis_iou.py
    python listen_basis_iou.py --port /dev/ttyACM0
    python listen_basis_iou.py --host 192.168.1.100

Requirements:
    pip install meshtastic pubsub
"""

import json
import time
import sys
import argparse
from datetime import datetime

try:
    from meshtastic.serial_interface import SerialInterface
    from meshtastic.tcp_interface import TCPInterface
    from meshtastic.ble_interface import BLEInterface
    from pubsub import pub
except ImportError as e:
    print(f"Error: Missing dependency - {e.name}")
    print("Install with: pip install meshtastic pubsub")
    sys.exit(1)


# Statistics
stats = {
    'total_messages': 0,
    'basis_messages': 0,
    'start_time': time.time()
}


def on_receive(packet, interface):
    """Callback for received packets."""
    stats['total_messages'] += 1
    
    if 'decoded' not in packet or 'payload' not in packet['decoded']:
        return
    
    payload = packet['decoded']['payload']
    
    # Try to decode as text
    try:
        text = payload.decode('utf-8')
        
        # Try to parse as JSON
        try:
            data = json.loads(text)
            
            # Check if it's a Basis IOU message
            msg_type = data.get('type') or data.get('t')
            if msg_type in ['iou_transfer', 'iou']:
                stats['basis_messages'] += 1
                
                # Extract fields (support both full and compact format)
                payer = data.get('payer') or data.get('p', 'unknown')
                payee = data.get('payee') or data.get('y', 'unknown')
                amount = data.get('amount') or data.get('a', 0)
                message = data.get('message') or data.get('m', '')
                timestamp = data.get('timestamp') or data.get('ts', 0)
                
                # Convert timestamp to datetime
                if timestamp:
                    dt = datetime.fromtimestamp(timestamp / 1000)
                    time_str = dt.strftime('%Y-%m-%d %H:%M:%S')
                else:
                    time_str = 'unknown'
                
                # Print formatted message
                print()
                print("=" * 60)
                print("  📬 Received Basis IOU!")
                print("=" * 60)
                print(f"  From:    {packet.get('fromId', 'unknown')}")
                print(f"  Payer:   {payer}")
                print(f"  Payee:   {payee}")
                print(f"  Amount:  {amount / 1e9:.9f} ERG ({amount} nanoERG)")
                print(f"  Message: {message}")
                print(f"  Time:    {time_str}")
                print(f"  RX RSSI: {packet.get('rxRssi', 'N/A')} dBm")
                print(f"  RX SNR:  {packet.get('rxSnr', 'N/A')} dB")
                print("=" * 60)
                
                # Save to file
                save_iou(packet, data)
                
        except json.JSONDecodeError:
            pass  # Not JSON
    except UnicodeDecodeError:
        pass  # Not text


def save_iou(packet: dict, data: dict):
    """Save IOU to file for later processing."""
    timestamp = int(time.time())
    filename = f"iou_{timestamp}.json"
    
    record = {
        'received_at': datetime.now().isoformat(),
        'from_node': packet.get('fromId'),
        'to_node': packet.get('toId'),
        'iou_data': data,
        'rx_info': {
            'rssi': packet.get('rxRssi'),
            'snr': packet.get('rxSnr'),
            'hop_limit': packet.get('hopLimit'),
        }
    }
    
    try:
        with open(filename, 'w') as f:
            json.dump(record, f, indent=2)
        print(f"  💾 Saved to: {filename}")
    except Exception as e:
        print(f"  ⚠️  Could not save: {e}")


def print_stats():
    """Print statistics."""
    elapsed = time.time() - stats['start_time']
    hours = elapsed / 3600
    
    print()
    print("=" * 60)
    print("  Statistics")
    print("=" * 60)
    print(f"  Runtime:         {elapsed/60:.1f} minutes")
    print(f"  Total messages:  {stats['total_messages']}")
    print(f"  Basis IOUs:      {stats['basis_messages']}")
    if elapsed > 0:
        print(f"  Msgs/hour:       {stats['total_messages']/hours:.1f}")
    print("=" * 60)


def main():
    parser = argparse.ArgumentParser(description='Listen for Basis IOU messages')
    parser.add_argument('--port', default='/dev/ttyUSB0', help='Serial port')
    parser.add_argument('--host', help='TCP host (alternative to serial)')
    parser.add_argument('--ble', action='store_true', help='Use BLE connection')
    parser.add_argument('--debug', action='store_true', help='Show all messages')
    parser.add_argument('--no-save', action='store_true', help='Do not save IOUs to file')
    
    args = parser.parse_args()
    
    print("=" * 60)
    print("  Basis IOU Listener")
    print("=" * 60)
    print(f"  Port:  {args.port if not args.host else args.host}")
    print(f"  Mode:  {'BLE' if args.ble else 'Serial' if not args.host else 'TCP'}")
    print("=" * 60)
    print()
    print("Listening for Basis IOU messages...")
    print("Press Ctrl+C to exit")
    print()
    
    # Subscribe to receive events
    pub.subscribe(on_receive, 'meshtastic.receive')
    
    # Connect to device
    try:
        if args.ble:
            interface = BLEInterface()
        elif args.host:
            interface = TCPInterface(args.host)
        else:
            interface = SerialInterface(args.port)
        
        print("Connected!")
        print()
        
        # Main loop
        while True:
            time.sleep(1)
            
            # Print stats every 5 minutes
            if int(time.time()) % 300 == 0 and stats['total_messages'] > 0:
                print_stats()
                
    except KeyboardInterrupt:
        print("\n\nExiting...")
        print_stats()
        interface.close()
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)


if __name__ == '__main__':
    main()
