# Basis Mesh Network Demo

## Community Trading Over Mesh Networks

This demo showcases **Basis protocol** enabling peer-to-peer credit-based trading in disconnected or intermittently-connected communities using mesh networking technology.

---

## Vision

### Local Trust, Global Settlement

The demo demonstrates how communities can:
- Trade locally **without Internet connectivity**
- Use **credit-based relationships** backed by trust
- Sync with blockchain when connectivity is available
- Enable **AI agents** to participate in autonomous economic relationships

---

## Architecture

```
Disconnected Village (Mesh Network)
┌─────────────────────────────────────────────────────────┐
│                                                         │
│  ┌──────┐    ┌──────┐    ┌──────┐                      │
│  │Alice │◄──►│  Bob │◄──►│Carol │                      │
│  │Phone │    │Phone │    │Phone │                      │
│  └──┬───┘    └──┬───┘    └──┬───┘                      │
│     │           │           │                           │
│     │    ┌──────┴──────┐    │                           │
│     └───►│   Tracker   │◄───┘                           │
│          │   (Mesh)    │                               │
│          └──────┬──────┘                               │
│                 │                                       │
│          ╭──────┴──────╮                               │
│          │  Gateway    │◄────── Internet ─────────────►│
│          │  (Online)   │         (Blockchain)          │
│          └─────────────┘                               │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### Components

1. **Mesh Nodes** (Alice, Bob, Carol phones)
   - Basis wallet app
   - Bluetooth/WiFi Direct mesh connectivity
   - Local IOU creation and transfer
   - Offline transaction signing

2. **Local Tracker** (Community server)
   - Maintains offchain debt ledger
   - Signs IOU notes for redemption
   - Syncs with mesh nodes locally
   - Aggregates transactions for blockchain sync

3. **Gateway Node** (Internet bridge)
   - Connects mesh to Ergo blockchain
   - Submits reserve transactions
   - Fetches blockchain state
   - Broadcasts redemption transactions

---

## Demo Scenarios

### Scenario 1: Local Credit Trading (Offline)

**Setup:**
- Alice and Bob in a village without Internet
- Both connected via mesh (Bluetooth/WiFi Direct/LoRa)
- Local tracker running on community server

**Flow:**
```
1. Alice buys goods from Bob (50 ERG value)
   ├─ Alice's device creates IOU note
   ├─ Bob's device receives IOU via mesh
   ├─ Tracker signs the IOU (local sync)
   └─ Transaction complete (no blockchain)

2. Alice buys more goods (30 ERG value)
   ├─ New IOU created and signed
   ├─ Bob now holds 80 ERG in IOUs from Alice
   └─ Tracker updates ledger

3. Gateway syncs (when Internet available)
   ├─ Bob aggregates IOUs (80 ERG total)
   ├─ Gateway submits redemption transaction
   └─ ERG credited to Bob's on-chain address
```

**Key Features Demonstrated:**
- ✅ Offline transaction creation
- ✅ Mesh propagation of IOU notes
- ✅ Local tracker signature
- ✅ Credit-based trading without collateral
- ✅ Batch settlement to reduce fees

---

### Scenario 2: Blockchain Settlement (Online)

**Setup:**
- Gateway node connects to Internet periodically
- Alice and Bob sync with gateway when available

**Flow:**
```
1. Gateway fetches blockchain state
   ├─ Downloads latest reserve boxes
   ├─ Updates local tracker state
   └─ Broadcasts to Alice and Bob

2. Bob redeems accumulated IOUs (80 ERG)
   ├─ Gateway creates redemption transaction
   ├─ Tracker provides signature
   ├─ Transaction submitted to Ergo
   └─ ERG credited to Bob's on-chain address

3. Alice tops up reserve
   ├─ Alice locks 100 ERG as collateral
   ├─ Reserve box created on-chain
   ├─ Gateway syncs to mesh
   └─ Alice can issue more IOUs
```

**Key Features Demonstrated:**
- ✅ Intermittent connectivity support
- ✅ Batch settlement to reduce fees
- ✅ Reserve creation with ERG collateral
- ✅ Redemption against on-chain reserves

---

### Scenario 3: Micropayments for Content

**Setup:**
- Bob offers digital content (articles, reports)
- Alice pays per item with small IOUs
- Aggregated redemption weekly

**Flow:**
```
1. Alice accesses content (0.5 ERG)
   ├─ Creates micro-IOU
   ├─ Bob accepts (trusts Alice)
   └─ Tracker logs transaction

2. Alice accesses 20 items (10 ERG total)
   ├─ IOUs accumulate
   └─ Bob holds Alice's debt

3. Weekly settlement
   ├─ Bob aggregates all IOUs
   ├─ Submits batch redemption
   └─ ERG credited to Bob
```

**Key Features Demonstrated:**
- ✅ Micropayments without on-chain fees
- ✅ Trust-based acceptance
- ✅ Aggregated settlement
- ✅ Content monetization

---

## Implementation Steps

### Concrete Steps to Showcase Community Trading Over Mesh

This section provides step-by-step instructions to implement and demonstrate Alice-Bob trading over a mesh network.

---

### Phase 1: Setup Hardware (Day 1-2)

**Required Hardware:**
- 2x Meshtastic devices (Rak4631, T-Beam, or Heltec WiFi LoRa)
- 1x Computer for tracker server (Raspberry Pi or laptop)
- 1x Computer for gateway (any Internet-connected device)

**Step 1.1: Flash Meshtastic Firmware**
```bash
# Follow https://meshtastic.org/docs/getting-started/flashing-firmware
# Flash latest firmware to both devices
```

**Step 1.2: Configure Devices**
```bash
# Device 1 (Alice)
meshtastic --port /dev/ttyUSB0 --set owner.long_name "Alice"
meshtastic --port /dev/ttyUSB0 --set owner.short_name "A"

# Device 2 (Bob)
meshtastic --port /dev/ttyUSB0 --set owner.long_name "Bob"
meshtastic --port /dev/ttyUSB0 --set owner.short_name "B"

# Set same channel and encryption key on both
meshtastic --port /dev/ttyUSB0 --set channel.psks[0].key "AQ=="
```

**Step 1.3: Test Connectivity**
```bash
# From Alice's device, send test message
meshtastic --port /dev/ttyUSB0 --sendtext "Hello Bob"

# On Bob's device, listen for messages
meshtastic --port /dev/ttyUSB0
```

---

### Phase 2: Setup Tracker Server (Day 3-4)

**Step 2.1: Install Dependencies**
```bash
# On tracker server (Raspberry Pi/laptop)
pip install meshtastic

# Clone ChainCash repo
git clone https://github.com/ChainCashLabs/chaincash.git
cd chaincash
```

**Step 2.2: Configure Tracker**
```bash
# Copy participants template
cp participants.csv.template participants.csv

# Edit with Alice and Bob details
# Format: name,address,secret_hex
alice,9f7ZX...,<alice_secret>
bob,9f7ZY...,<bob_secret>
tracker,9f7ZZ...,<tracker_secret>
```

**Step 2.3: Start Tracker Service**
```bash
# Start tracker server (listens for IOU requests)
# This maintains the debt ledger
python3 -m chaincash.offchain.tracker --config tracker.conf
```

---

### Phase 3: First IOU Transaction (Day 5)

**Step 3.1: Alice Creates IOU**
```bash
# On Alice's device (or via tracker)
cd demo/mesh
./send_basis_message.sh alice bob 50000000 "Goods payment"
```

**Step 3.2: Bob Receives IOU**
```bash
# On Bob's device (listening)
python3 listen_basis_iou.py --port /dev/ttyUSB0

# Should see:
# 📬 Received Basis IOU!
# From:    !alice_node_id
# Payer:   alice
# Payee:   bob
# Amount:  0.050000000 ERG
```

**Step 3.3: Tracker Signs IOU**
```bash
# Tracker automatically signs and logs
# Check tracker logs:
tail -f tracker.log

# Should show:
# [INFO] Signed IOU: alice->bob 50000000 nanoERG
# [INFO] Ledger updated: bob balance +50000000
```

**Step 3.4: Verify in Ledger**
```bash
# Check tracker ledger
cat ledger.json

# Should show:
{
  "alice": {"issued": 50000000, "redeemed": 0},
  "bob": {"received": 50000000, "redeemed": 0}
}
```

---

### Phase 4: Multiple Transactions (Day 6)

**Step 4.1: Alice Buys More**
```bash
# Second transaction
./send_basis_message.sh alice bob 30000000 "More goods"

# Third transaction
./send_basis_message.sh alice bob 20000000 "Services"
```

**Step 4.2: Check Total Balance**
```bash
# Bob's total IOUs from Alice
python3 -c "
import json
with open('ledger.json') as f:
    ledger = json.load(f)
    print(f'Bob holds: {ledger[\"bob\"][\"received\"] / 1e9} ERG from Alice')
"

# Should show: Bob holds: 0.100000000 ERG from Alice
```

---

### Phase 5: Gateway Settlement (Day 7)

**Step 5.1: Connect Gateway to Internet**
```bash
# On gateway machine (Internet-connected)
export ERGO_NODE_URL=http://node.api.url:9053
export ERGO_API_KEY=your_api_key
```

**Step 5.2: Sync Blockchain State**
```bash
# Fetch latest reserve boxes
python3 -m chaincash.offchain.gateway --sync

# Should show:
# [INFO] Synced 1 reserve boxes
# [INFO] Synced 1 tracker boxes
```

**Step 5.3: Bob Redeems IOUs**
```bash
# Bob aggregates and redeems
python3 -m chaincash.offchain.gateway --redeem --holder bob

# Should show:
# [INFO] Aggregated 3 IOUs totaling 0.1 ERG
# [INFO] Redemption transaction created
# [INFO] TxId: <transaction_id>
# [INFO] Awaiting confirmation...
```

**Step 5.4: Verify On-Chain**
```bash
# Check Ergo explorer
curl https://api.ergoplatform.com/transactions/<transaction_id>

# Check Bob's balance
curl https://api.ergoplatform.com/addresses/bob_address

# Should show increased ERG balance
```

---

### Phase 6: Demo Presentation (Day 8)

**Step 6.1: Prepare Demo Script**
```bash
# Create demo script
cat > demo_script.md << 'EOF'
# Mesh Trading Demo Script

## Setup (5 min)
1. Show Alice and Bob devices (Meshtastic)
2. Show tracker server running
3. Show ledger (empty initially)

## Transaction 1 (5 min)
1. Alice sends IOU: ./send_basis_message.sh alice bob 50000000
2. Bob receives on device (show screen)
3. Tracker signs (show log)
4. Ledger updated (show JSON)

## Transaction 2 (3 min)
1. Alice sends another IOU
2. Bob's balance increases
3. Explain: No blockchain yet!

## Settlement (5 min)
1. Connect gateway to Internet
2. Bob redeems: python3 gateway.py --redeem
3. Show transaction on Ergo explorer
4. Bob's balance updated on-chain

## Summary (2 min)
- Offline trading works via mesh
- Tracker maintains debt ledger
- Blockchain used only for final settlement
- No forced collateralization
EOF
```

**Step 6.2: Record Demo Video**
```bash
# Record screen during demo
ffmpeg -f x11grab -video_size 1920x1080 -i :0.0 -c:v libx264 demo.mp4

# Or use OBS Studio for better quality
```

**Step 6.3: Create Demo Report**
```bash
# Document results
cat > demo_report.md << 'EOF'
# Mesh Trading Demo Report

## Date: 2026-03-29
## Participants: Alice, Bob
## Location: [Your location]

## Results

### Transactions Completed
- Transaction 1: 0.05 ERG (Alice→Bob) ✅
- Transaction 2: 0.03 ERG (Alice→Bob) ✅
- Transaction 3: 0.02 ERG (Alice→Bob) ✅

### Settlement
- Total redeemed: 0.10 ERG ✅
- On-chain transaction: <tx_id>
- Confirmation time: ~2 minutes

### Performance
- Message latency: <1 second (mesh)
- Settlement time: ~2 minutes (blockchain)
- Message size: ~150 bytes
- Range: ~200 meters (urban)

## Conclusion
Successfully demonstrated offline credit trading between Alice and Bob
using Meshtastic mesh network with Basis protocol.
EOF
```

---

## Technical Implementation

### Mesh Network Stack

```
┌─────────────────────────────────────┐
│         Alice's Device              │
│       (Meshtastic + Basis)          │
├─────────────────────────────────────┤
│    IOU Creation & Signing           │
├─────────────────────────────────────┤
│      Mesh Message Layer             │
│    (LoRa / Bluetooth / WiFi)        │
├─────────────────────────────────────┤
│    Physical Radio                   │
└─────────────────────────────────────┘
              ↕ (mesh radio)
┌─────────────────────────────────────┐
│         Bob's Device                │
│       (Meshtastic + Basis)          │
├─────────────────────────────────────┤
│    IOU Reception & Verification     │
├─────────────────────────────────────┤
│      Mesh Message Layer             │
│    (LoRa / Bluetooth / WiFi)        │
├─────────────────────────────────────┤
│    Physical Radio                   │
└─────────────────────────────────────┘
              ↕ (USB/Serial)
┌─────────────────────────────────────┐
│       Tracker Server                │
│    (Community Ledger Keeper)        │
├─────────────────────────────────────┤
│    Debt Ledger Database             │
│    IOU Signing Service              │
└─────────────────────────────────────┘
              ↕ (Internet when available)
┌─────────────────────────────────────┐
│        Gateway Node                 │
│    (Blockchain Bridge)              │
├─────────────────────────────────────┤
│    Ergo Node Connection             │
│    Settlement Service               │
└─────────────────────────────────────┘
```

### Message Types

1. **IOU Transfer** (Compact format for LoRa)
   ```json
   {"t":"iou","v":"1.0","p":"alice","y":"bob","a":50000000,"c":"nanoERG","m":"Goods payment","ts":1704000000000}
   ```

2. **Tracker Sync**
   ```json
   {
     "type": "tracker_sync",
     "ledgerUpdate": [{"payer":"alice","payee":"bob","amount":50000000}],
     "trackerSignature": "...",
     "sequenceNumber": 42
   }
   ```

3. **Blockchain State**
   ```json
   {
     "type": "blockchain_state",
     "reserveBoxes": [...],
     "trackerBox": {...},
     "blockHeight": 1750000,
     "gatewaySignature": "..."
   }
   ```

### Security Considerations

1. **Mesh Layer**
   - End-to-end encryption for IOU transfers
   - Device authentication via public keys
   - Replay attack prevention (timestamps, sequence numbers)

2. **Tracker**
   - Cannot steal funds (requires reserve owner signature)
   - Cannot forge IOUs (requires payer signature)
   - Auditable ledger (all signatures recorded)

3. **Blockchain**
   - Final settlement layer
   - Collateral locking for trustless issuance
   - Public audit trail

---

## Testing Checklist

### Hardware Testing
- [ ] Both Meshtastic devices flash successfully
- [ ] Devices can communicate (ping test)
- [ ] Range test: 100m, 200m, 500m
- [ ] Battery life acceptable (>4 hours)

### Software Testing
- [ ] `send_basis_message.sh` sends IOU successfully
- [ ] `listen_basis_iou.py` receives IOU
- [ ] Tracker signs IOU correctly
- [ ] Ledger updates properly

### Integration Testing
- [ ] End-to-end: Alice→Bob IOU created and received
- [ ] Multiple IOUs accumulate correctly
- [ ] Gateway syncs with blockchain
- [ ] Redemption transaction succeeds

### Field Testing
- [ ] Works without Internet (offline mode)
- [ ] Intermittent connectivity handled
- [ ] Real-world range (urban environment)
- [ ] Demo runs smoothly end-to-end

---

## Success Metrics

### Technical
- Message latency: < 1 second (mesh)
- Settlement time: < 10 minutes (blockchain)
- Message size: < 180 bytes (LoRa compatible)
- Range: > 100 meters (urban)

### Economic
- IOUs created: Multiple transactions
- Total value: Test with real ERG amounts
- Settlement: Successful on-chain redemption
- Default rate: 0% (all IOUs redeemed)

---

## Future Enhancements

1. **Multi-Tracker Support**
   - Redundant trackers for fault tolerance
   - Seamless tracker migration

2. **Cross-Mesh Trading**
   - Bridge different mesh networks
   - Inter-community credit

3. **Mobile App**
   - Android/iOS wallet app
   - Built-in Meshtastic support
   - QR code IOU sharing

4. **Advanced Features**
   - Payment channels for frequent traders
   - Conditional IOUs (escrow)
   - Recurring payments

---

## Resources

### Documentation
- [Meshtastic Integration Guide](./MESHTASTIC.md) - Complete guide for sending Basis messages over Meshtastic
- [Basis Protocol Whitepaper](../../docs/conf/conf.pdf)
- [Presentation](../../docs/presentation/presentation.md)
- [Ergo Documentation](https://docs.ergoplatform.com/)
- [Mesh Networking Protocols](https://en.wikipedia.org/wiki/Mesh_networking)

### Code & Scripts
- `send_basis_message.sh` - Bash script for sending IOUs via Meshtastic CLI
- `send_basis_iou.py` - Python script with advanced options
- `listen_basis_iou.py` - Python script for receiving IOUs

### Hardware
- [Meshtastic Project](https://meshtastic.org/) - LoRa mesh networking
- [Meshtastic CLI](https://meshtastic.org/docs/software/python/cli/usage/) - Command-line interface
- [Supported Devices](https://meshtastic.org/docs/hardware/devices/) - Rak4631, T-Beam, Heltec, etc.

### Community
- Telegram: [t.me/chaincashtalks](https://t.me/chaincashtalks)
- Twitter: [@ChainCashLabs](https://twitter.com/ChainCashLabs)

---

## License

This demo is part of the ChainCash project, released under a permissive open-source license. See [LICENSE](../../LICENSE) for details.

---

**Built by and for the Commons** 🌱

Free, open source community project. No token, no VC, no corporate control.

**For Disconnected Communities** 📡

Enabling offline credit trading via mesh networks.
