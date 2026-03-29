# Mesh Network Demo - Implementation Plan

## Division of Work: AI Assistant vs Human Implementation

This document separates what has been implemented (by AI assistant) from what humans need to do to complete the showcase.

---

## Part 1: AI Assistant Implementation ✅ (COMPLETE)

### What Has Been Delivered

The AI assistant has created all **software artifacts** needed for the demo:

#### 1. Documentation (Complete)
- [x] `README.md` - Main demo documentation with scenarios
- [x] `MESHTASTIC.md` - Meshtastic integration guide
- [x] `MCP_SERVER.md` - MCP server specification
- [x] `IMPLEMENTATION_PLAN.md` - This document

#### 2. Scripts (Complete & Ready to Use)
- [x] `send_basis_message.sh` - Bash script for sending IOUs
- [x] `send_basis_iou.py` - Python script with advanced options
- [x] `listen_basis_iou.py` - Python script for receiving IOUs

#### 3. Specifications (Complete)
- [x] Message format (compact JSON for LoRa)
- [x] IOU structure (payer, payee, amount, timestamp)
- [x] API documentation (all script options)
- [x] Testing checklist

#### 4. Integration Design (Complete)
- [x] Meshtastic CLI integration
- [x] MCP server specification for AI assistants
- [x] Tracker server architecture
- [x] Gateway settlement flow

---

## Part 2: Human Implementation Required 📋 (TODO)

### What Humans Need to Do

Humans must complete **physical setup** and **real-world testing**:

---

### Phase 1: Hardware Setup (2-3 days)

**Responsibility:** Human team

**Required Skills:** Basic electronics, USB configuration

#### Step 1.1: Purchase Hardware

**Shopping List:**
```
□ 2x Meshtastic devices (choose one):
  - Rak4631 (~$50 each) - Recommended for beginners
  - T-Beam (~$35 each) - Has GPS
  - Heltec WiFi LoRa 32 (~$25 each) - Has WiFi

□ 1x Raspberry Pi 4 (for tracker server) - Optional
  - Or use any laptop/PC

□ 1x Internet-connected device (for gateway)
  - Can be same as tracker server

□ Micro-USB cables (2x)
□ Antennas (if not included)
```

**Estimated Cost:** $100-150 for basic setup

#### Step 1.2: Flash Firmware

**Human Action Required:**
```bash
# Human must:
1. Connect device via USB
2. Download firmware from https://meshtastic.org
3. Flash using web flasher or dfu-util
4. Verify device shows up as /dev/ttyUSB0
```

**AI Cannot Do:**
- Physical USB connections
- Hardware troubleshooting
- Firmware flashing (requires manual steps)

#### Step 1.3: Configure Devices

**Human Action Required:**
```bash
# Run these commands (AI provided, human executes):

# Device 1 (Alice)
meshtastic --port /dev/ttyUSB0 --set owner.long_name "Alice"
meshtastic --port /dev/ttyUSB0 --set owner.short_name "A"

# Device 2 (Bob)
meshtastic --port /dev/ttyUSB0 --set owner.long_name "Bob"
meshtastic --port /dev/ttyUSB0 --set owner.short_name "B"

# Set encryption (both devices)
meshtastic --port /dev/ttyUSB0 --set channel.psks[0].key "AQ=="
```

**Success Criteria:**
- [ ] Both devices show correct names
- [ ] Devices can ping each other
- [ ] Range test: 100m minimum

---

### Phase 2: Software Installation (1 day)

**Responsibility:** Human developer

**Required Skills:** Python, bash, git

#### Step 2.1: Install Dependencies

**Human Action Required:**
```bash
# Install Python packages
pip install meshtastic pubsub

# Verify installation
meshtastic --version
# Should show: meshtastic 2.x.x

# Clone repository (if not already done)
git clone https://github.com/ChainCashLabs/chaincash.git
cd chaincash/demo/basis/mesh
```

#### Step 2.2: Test Scripts

**Human Action Required:**
```bash
# Make scripts executable
chmod +x *.sh *.py

# Test send script (will fail without device - that's OK)
./send_basis_message.sh --help

# Test listen script
python3 listen_basis_iou.py --help
```

**Success Criteria:**
- [ ] `meshtastic --version` works
- [ ] Scripts show help text
- [ ] No import errors

---

### Phase 3: First Transaction Test (1 day)

**Responsibility:** Human team (2 people recommended)

**Required Skills:** Basic command line

#### Step 3.1: Setup Test Environment

**Human Action Required:**
```bash
# Person 1 (Alice):
cd /path/to/chaincash/demo/basis/mesh
# Keep terminal open

# Person 2 (Bob):
cd /path/to/chaincash/demo/basis/mesh
# Keep terminal open
```

#### Step 3.2: Bob Starts Listening

**Human Action Required (Bob):**
```bash
# Start listener
python3 listen_basis_iou.py --port /dev/ttyUSB0

# Expected output:
# ==================================================
#   Basis IOU Listener
# ==================================================
# Listening for Basis IOU messages...
# Press Ctrl+C to exit
```

#### Step 3.3: Alice Sends IOU

**Human Action Required (Alice):**
```bash
# Send test IOU
./send_basis_message.sh alice bob 50000000 "Test payment"

# Expected output:
# ==================================================
#   Basis IOU over Meshtastic
# ==================================================
#   Payer:     alice
#   Payee:     bob
#   Amount:    0.050000000 ERG
#   Message:   Test payment
# ==================================================
# ✓ Message sent successfully!
```

#### Step 3.4: Verify Reception

**Human Action Required (Bob):**
```bash
# Check listener output - should show:
# ==================================================
#   📬 Received Basis IOU!
# ==================================================
#   From:    !12345678
#   Payer:   alice
#   Payee:   bob
#   Amount:  0.050000000 ERG
#   Message: Test payment
# ==================================================
#   💾 Saved to: iou_1711732200.json
```

**Success Criteria:**
- [ ] Message sent without errors
- [ ] Bob received IOU
- [ ] IOU saved to JSON file
- [ ] Signal strength reasonable (> -90 dBm)

---

### Phase 4: Tracker Server Setup (1-2 days)

**Responsibility:** Human developer

**Required Skills:** Python, system administration

#### Step 4.1: Configure Tracker

**Human Action Required:**
```bash
# Copy and edit participants file
cp ../../participants.csv.template participants.csv
nano participants.csv

# Add entries:
# name,address,secret_hex
# alice,9f7ZX...,<generate_secret>
# bob,9f7ZY...,<generate_secret>
# tracker,9f7ZZ...,<generate_secret>
```

**Note:** AI cannot generate secrets for production use.

#### Step 4.2: Start Tracker Service

**Human Action Required:**
```bash
# Start tracker (command TBD - tracker not yet implemented)
python3 -m chaincash.offchain.tracker --config tracker.conf

# Should show:
# [INFO] Tracker started
# [INFO] Listening for IOU requests
# [INFO] Ledger initialized
```

**AI Limitation:** Tracker server code not yet written.

#### Step 4.3: Test Tracker Signing

**Human Action Required:**
```bash
# Send IOU (should be auto-signed by tracker)
./send_basis_message.sh alice bob 50000000 "Tracker test"

# Check tracker logs
tail -f tracker.log

# Should show:
# [INFO] Received IOU request: alice->bob 50000000
# [INFO] Signed IOU: abc123...
# [INFO] Ledger updated
```

**Success Criteria:**
- [ ] Tracker starts without errors
- [ ] IOUs are signed automatically
- [ ] Ledger updates correctly

---

### Phase 5: Gateway Settlement (1-2 days)

**Responsibility:** Human developer

**Required Skills:** Ergo blockchain, API integration

#### Step 5.1: Setup Ergo Node Connection

**Human Action Required:**
```bash
# Set environment variables
export ERGO_NODE_URL=http://localhost:9053
export ERGO_API_KEY=your_api_key

# Or use public node
export ERGO_NODE_URL=https://api.ergoplatform.com
```

#### Step 5.2: Create Reserve (Alice)

**Human Action Required:**
```bash
# Alice creates reserve with collateral
python3 -m chaincash.offchain.gateway --create-reserve \
  --amount 100000000 \
  --owner alice

# Should create on-chain transaction
# TxId: <transaction_hash>
```

**AI Limitation:** Gateway code not yet fully implemented.

#### Step 5.3: Redeem IOUs (Bob)

**Human Action Required:**
```bash
# Bob redeems accumulated IOUs
python3 -m chaincash.offchain.gateway --redeem \
  --holder bob \
  --iou-files iou_*.json

# Should show:
# [INFO] Aggregated 3 IOUs totaling 0.1 ERG
# [INFO] Redemption transaction created
# [INFO] TxId: <hash>
```

#### Step 5.4: Verify On-Chain

**Human Action Required:**
```bash
# Check Ergo explorer
curl https://api.ergoplatform.com/transactions/<tx_id>

# Check Bob's balance
curl https://api.ergoplatform.com/addresses/bob_address

# Should show increased ERG balance
```

**Success Criteria:**
- [ ] Reserve created on-chain
- [ ] Redemption transaction confirmed
- [ ] Bob's balance increased

---

### Phase 6: Demo Presentation (1 day)

**Responsibility:** Human team

**Required Skills:** Presentation, video recording

#### Step 6.1: Prepare Demo Script

**Human Action Required:**
```markdown
# Demo Script (20 minutes total)

## Introduction (2 min)
- Show hardware (2 Meshtastic devices)
- Explain offline trading concept
- Show Alice and Bob locations

## Live Demo (15 min)
1. Alice sends IOU: ./send_basis_message.sh (3 min)
2. Bob receives on device (show screen) (2 min)
3. Send 2 more IOUs (3 min)
4. Show accumulated balance (2 min)
5. Gateway redemption (5 min)

## Q&A (3 min)
- Answer questions
- Show code repository
```

#### Step 6.2: Record Demo Video

**Human Action Required:**
```bash
# Record screen
obs-studio  # or similar

# Or use ffmpeg
ffmpeg -f x11grab -video_size 1920x1080 -i :0.0 demo.mp4
```

#### Step 6.3: Create Demo Report

**Human Action Required:**
```markdown
# Demo Report Template

## Date: YYYY-MM-DD
## Location: [Your location]
## Participants: [Names]

## Results
- Transactions completed: X/Y
- Total value: X ERG
- Settlement time: X minutes
- Range tested: X meters

## Issues Encountered
[List any problems and solutions]

## Conclusion
[Success/failure and next steps]
```

---

## Summary: AI vs Human Responsibilities

### AI Assistant Delivers ✅

| Artifact | Status | Location |
|----------|--------|----------|
| Documentation | ✅ Complete | `demo/basis/mesh/*.md` |
| Send scripts | ✅ Complete | `demo/basis/mesh/*.sh, *.py` |
| Listen scripts | ✅ Complete | `demo/basis/mesh/listen_*.py` |
| Message formats | ✅ Complete | `MESHTASTIC.md` |
| MCP server spec | ✅ Complete | `MCP_SERVER.md` |
| Testing checklist | ✅ Complete | `README.md` |

### Humans Must Do 📋

| Task | Estimated Time | Skills Required |
|------|---------------|-----------------|
| Hardware purchase | 1-2 days | Shopping |
| Firmware flashing | 2-4 hours | Basic tech |
| Device configuration | 1-2 hours | Command line |
| Software installation | 2-4 hours | Python |
| Transaction testing | 1 day | 2 people |
| Tracker setup | 1-2 days | Python dev |
| Gateway setup | 1-2 days | Blockchain dev |
| Demo preparation | 1 day | Presentation |
| Field testing | 1-2 days | 2+ people |

**Total Human Effort:** 10-15 days (with 2-3 people)

---

## Critical Path

```
Hardware Purchase → Firmware Flash → First Transaction → Tracker Setup → Gateway Setup → Demo
     (2 days)          (4 hours)       (1 day)           (2 days)        (2 days)       (1 day)
```

**Minimum Time to Demo:** 7-8 days (accelerated)  
**Recommended Time:** 10-15 days (comfortable pace)

---

## Dependencies Not Yet Implemented

The following components are **specified but not implemented**:

1. **Tracker Server** (`chaincash.offchain.tracker`)
   - Spec: `demo/basis/agents/SPEC.md`
   - Status: Not implemented
   - Human action: Implement or use mock

2. **Gateway Service** (`chaincash.offchain.gateway`)
   - Spec: `demo/basis/agents/SPEC.md`
   - Status: Not implemented
   - Human action: Implement or use mock

3. **Mobile App**
   - Spec: Future enhancement
   - Status: Not started
   - Human action: Future work

**Workaround:** Use scripts directly without full tracker/gateway for initial demo.

---

## Next Steps for Humans

### Immediate (This Week)
1. [ ] Order hardware (2x Meshtastic devices)
2. [ ] Install Python dependencies (`pip install meshtastic`)
3. [ ] Test scripts with `--help` flag

### Short Term (Next Week)
4. [ ] Flash firmware on devices
5. [ ] Configure Alice and Bob devices
6. [ ] Test first IOU transaction

### Medium Term (2-3 Weeks)
7. [ ] Implement tracker server (or use mock)
8. [ ] Implement gateway service (or use mock)
9. [ ] Record demo video

### Long Term (1-2 Months)
10. [ ] Field testing in real environment
11. [ ] Mobile app development
12. [ ] Production deployment

---

**Questions?** See documentation in `demo/basis/mesh/` or contact the team.

**Status:** AI implementation complete ✅ | Human implementation pending 📋
