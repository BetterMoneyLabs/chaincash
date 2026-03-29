# Meshtastic MCP Server Specification

## Model Context Protocol Server for Meshtastic Mesh Networking

This document specifies an MCP server that enables AI assistants to interact with Meshtastic mesh networks for sending and receiving Basis IOU messages.

---

## Overview

The Meshtastic MCP server provides these tools:
- `send_text` - Send text messages over mesh
- `send_basis_iou` - Send Basis IOU payments
- `listen_messages` - Listen for incoming messages
- `get_node_info` - Get local node information
- `get_mesh_nodes` - List visible mesh nodes

---

## Server Implementation

### Required Dependencies

```python
# requirements.txt
meshtastic>=2.0.0
mcp>=1.0.0
pydantic>=2.0.0
```

### Server Skeleton

```python
#!/usr/bin/env python3
"""
Meshtastic MCP Server

Usage:
    python mcp_server_meshtastic.py
    # Or via uvx
    uvx mcp-server-meshtastic
"""

import asyncio
import json
from typing import Any
from meshtastic.serial_interface import SerialInterface
from meshtastic.tcp_interface import TCPInterface
from mcp.server import Server
from mcp.server.stdio import stdio_server
from pydantic import BaseModel, Field

# Create server instance
server = Server("meshtastic")

# Global interface
interface = None

class SendTextArgs(BaseModel):
    message: str
    destination: str | None = None
    channel: int = 0

class SendBasisIOUArgs(BaseModel):
    payer: str
    payee: str
    amount: int  # nanoERG
    message: str = "Payment"
    destination: str | None = None
    compact: bool = True

class ListenMessagesArgs(BaseModel):
    duration: int = 30
    filter_iou: bool = False

@server.tool("send_text")
async def send_text(
    message: str,
    destination: str | None = None,
    channel: int = 0
) -> dict[str, Any]:
    """Send a text message over the mesh network."""
    global interface
    
    try:
        if interface is None:
            interface = SerialInterface()
        
        packet = interface.sendText(message, destinationId=destination)
        
        return {
            "success": True,
            "message_id": packet.get('id'),
            "timestamp": int(time.time() * 1000),
            "destination": destination or "broadcast"
        }
    except Exception as e:
        return {"success": False, "error": str(e)}

@server.tool("send_basis_iou")
async def send_basis_iou(
    payer: str, payee: str, amount: int,
    message: str = "Payment",
    destination: str | None = None,
    compact: bool = True
) -> dict[str, Any]:
    """Send a Basis IOU payment message over the mesh."""
    import time
    
    # Create IOU message
    if compact:
        iou_data = {
            "t": "iou", "v": "1.0", "p": payer, "y": payee,
            "a": amount, "c": "nanoERG", "m": message,
            "ts": int(time.time() * 1000)
        }
        message_text = json.dumps(iou_data, separators=(',', ':'))
    else:
        iou_data = {
            "type": "iou_transfer", "version": "1.0",
            "payer": payer, "payee": payee, "amount": amount,
            "currency": "nanoERG", "message": message,
            "timestamp": int(time.time() * 1000)
        }
        message_text = json.dumps(iou_data)
    
    result = await send_text(message_text, destination)
    
    if result["success"]:
        return {
            "success": True,
            "iou_id": f"iou_{int(time.time())}",
            "payer": payer, "payee": payee,
            "amount": amount, "amount_erg": amount / 1e9,
            "message_id": result["message_id"]
        }
    else:
        return result

@server.tool("listen_messages")
async def listen_messages(duration: int = 30, filter_iou: bool = False) -> dict[str, Any]:
    """Start listening for incoming messages."""
    global interface
    received = []
    
    def on_receive(packet, intf):
        try:
            if 'decoded' in packet and 'payload' in packet['decoded']:
                payload = packet['decoded']['payload']
                text = payload.decode('utf-8')
                data = json.loads(text)
                
                if filter_iou:
                    msg_type = data.get('type') or data.get('t')
                    if msg_type not in ('iou_transfer', 'iou'):
                        return
                
                received.append({
                    "type": data.get('type') or data.get('t'),
                    "from": packet.get('fromId'),
                    "data": data
                })
        except:
            pass
    
    try:
        if interface is None:
            interface = SerialInterface()
        
        from pubsub import pub
        pub.subscribe(on_receive, 'meshtastic.receive')
        await asyncio.sleep(duration)
        
        return {
            "success": True,
            "messages": received,
            "total_received": len(received),
            "duration": duration
        }
    except Exception as e:
        return {"success": False, "error": str(e)}

@server.tool("get_node_info")
async def get_node_info() -> dict[str, Any]:
    """Get information about the local Meshtastic node."""
    global interface
    
    try:
        if interface is None:
            interface = SerialInterface()
        
        my_info = interface.myInfo
        return {
            "success": True,
            "node": {
                "id": f"!{my_info.my_node_num:08x}",
                "long_name": my_info.long_name,
                "short_name": my_info.short_name
            }
        }
    except Exception as e:
        return {"success": False, "error": str(e)}

@server.tool("get_mesh_nodes")
async def get_mesh_nodes(recent_only: bool = True) -> dict[str, Any]:
    """Get list of nodes visible in the mesh."""
    global interface
    
    try:
        if interface is None:
            interface = SerialInterface()
        
        import time
        now = int(time.time())
        one_hour = 3600
        
        nodes = []
        for node_id, node in interface.nodes.items():
            last_seen = node.get('lastHeard', 0)
            if recent_only and (now - last_seen) > one_hour:
                continue
            
            nodes.append({
                "id": node_id,
                "long_name": node['user'].get('longName', 'Unknown'),
                "short_name": node['user'].get('shortName', '?'),
                "last_seen": last_seen
            })
        
        return {"success": True, "nodes": nodes, "total": len(nodes)}
    except Exception as e:
        return {"success": False, "error": str(e)}

async def main():
    async with stdio_server() as (read_stream, write_stream):
        await server.run(read_stream, write_stream, server.create_initialization_options())

if __name__ == "__main__":
    asyncio.run(main())
```

---

## Installation

```bash
# Create virtual environment
python -m venv venv
source venv/bin/activate

# Install dependencies
pip install meshtastic mcp pydantic

# Install server
pip install -e .

# Run server
python mcp_server_meshtastic.py
```

---

## Claude Desktop Configuration

Add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "meshtastic": {
      "command": "python",
      "args": ["/path/to/mcp_server_meshtastic.py"],
      "env": {
        "MESHTASTIC_PORT": "/dev/ttyUSB0"
      }
    }
  }
}
```

---

## Example Interactions

### Example 1: Send IOU Payment

**User:** "Send 0.05 ERG from Alice to Bob"

**Assistant uses tool:**
```json
{
  "name": "send_basis_iou",
  "arguments": {
    "payer": "alice",
    "payee": "bob",
    "amount": 50000000,
    "message": "Payment for goods",
    "destination": "!ba4bf9d0"
  }
}
```

**Response:**
```json
{
  "success": true,
  "iou_id": "iou_1711732200",
  "payer": "alice",
  "payee": "bob",
  "amount": 50000000,
  "amount_erg": 0.05,
  "message_id": "msg_12345"
}
```

**Assistant responds:** "✅ Successfully sent 0.05 ERG from Alice to Bob over the mesh network."

---

### Example 2: Check Mesh Status

**User:** "What nodes are visible?"

**Assistant uses tool:**
```json
{
  "name": "get_mesh_nodes",
  "arguments": {"recent_only": true}
}
```

**Response:**
```json
{
  "success": true,
  "nodes": [
    {
      "id": "!12345678",
      "long_name": "BasisTracker",
      "short_name": "BT",
      "last_seen": 1704000000,
      "snr": 9.5,
      "rssi": -72
    }
  ],
  "total": 1
}
```

---

## Security

### Encryption

All mesh messages use channel PSK encryption:

```python
def check_encryption():
    ch = interface.localNode.getChannelByIndex(0)
    return ch.settings.psk != b''
```

### Authentication

Verify message sender:

```python
def verify_sender(packet, expected):
    return packet.get('fromId') == expected
```

---

## Resources

- [MCP Specification](https://modelcontextprotocol.io/)
- [Meshtastic Documentation](https://meshtastic.org/)
- [Basis Protocol](../../README.md)

---

**Status:** Specification complete ✅ | Implementation skeleton provided 📋
