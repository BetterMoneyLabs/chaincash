# Basis Protocol Demos

## P2P Money for Humans & AI Agents

This directory contains demonstration scenarios for the **Basis protocol** - a peer-to-peer money creation system built on the Ergo blockchain.

> **Local Trust, Global Settlement**

---

## Overview

Basis enables:
- **Credit-based trading** without forced collateralization
- **Offline transactions** via mesh networks
- **Efficient multi-party settlement** via debt netting
- **Autonomous agent economies** without human intermediaries

---

## Demo Scenarios

### 1. Simple Basis Demo (`simple/`)

**Purpose:** Basic IOU creation and redemption flow

**What it demonstrates:**
- Creating IOU notes with tracker signature
- Transferring IOUs between parties
- Redeeming IOUs against reserve
- On-chain settlement

**Best for:**
- Understanding core protocol mechanics
- First-time users
- Testing basic functionality

**Get Started:**
```bash
cd demo/basis/simple
cat README.md
```

**Status:** ✅ Working (reference implementation)

---

### 2. Mesh Network Demo (`mesh/`)

**Purpose:** Offline trading via mesh networks

**What it demonstrates:**
- Alice-Bob trading without Internet
- Meshtastic LoRa integration
- Offline IOU creation and signing
- Gateway settlement when online

**What's Included:**
- `send_basis_message.sh` - Bash script for sending IOUs
- `send_basis_iou.py` - Python sender with advanced options
- `listen_basis_iou.py` - Python receiver/listener
- `MESHTASTIC.md` - Complete Meshtastic integration guide
- `MCP_SERVER.md` - AI assistant integration spec
- `IMPLEMENTATION_PLAN.md` - AI vs Human task division

**Best for:**
- Disconnected communities
- Offline-first scenarios
- Real-world mesh testing
- AI assistant integration

**Get Started:**
```bash
cd demo/basis/mesh
cat QUICKSTART.md  # or IMPLEMENTATION_PLAN.md
```

**Hardware Required:**
- 2x Meshtastic devices (~$100-150)
- Optional: Raspberry Pi for tracker

**Status:** ✅ Software complete | 📋 Human hardware setup needed

---

### 3. Circular Trading Demo (`circular/`)

**Purpose:** Triangular trade and debt netting

**What it demonstrates:**
- Debt transfer with consent
- Multi-party netting calculation
- Optimized settlement (61% reduction!)
- Circular debt cancellation

**What's Included:**
- `calculate_netting.py` - Net position calculation
- `transfer_debt.sh` - Debt transfer with consent
- `settle_netting.py` - Optimized settlement execution
- `visualize_circle.py` - Network visualization
- `sample_ledger.json` - Example trading data

**Best for:**
- Community trading circles
- Supply chain finance
- Freelancer collectives
- Reducing transaction fees

**Get Started:**
```bash
cd demo/basis/circular
python3 calculate_netting.py --ledger sample_ledger.json
```

**Example Result:**
```
Before: 3 transactions, 18 ERG total
After:  2 transactions, 7 ERG total
Savings: 61% reduction!
```

**Status:** ✅ Complete and tested

---

### 4. Agent Economy Demo (`agents/`)

**Purpose:** AI agents creating autonomous economic relationships

**What it demonstrates:**
- Agent-to-agent credit creation
- Autonomous task negotiation
- Multi-agent payment splitting
- Human reserve backing

**What's Included:**
- `README.md` - Vision, scenarios, architecture
- `SPEC.md` - Technical specification (12 sections)
- Agent wallet architecture
- Communication protocol specs
- Reputation/credit limit system

**Best for:**
- AI agent developers
- Autonomous economy research
- Agent-to-agent payments
- Human-agent collaboration

**Get Started:**
```bash
cd demo/basis/agents
cat README.md
```

**Example Scenario:**
```
Repo Agent → Dev Agent: 10 ERG (code development)
Dev Agent → Test Agent: 3 ERG (testing services)
All autonomous, no human intervention!
```

**Status:** 📋 Specification complete | Implementation pending

---

## Quick Comparison

| Demo | Focus | Hardware | Status | Time to Run |
|------|-------|----------|--------|-------------|
| **Simple** | Core protocol | None | ✅ Complete | 10 minutes |
| **Mesh** | Offline trading | 2x Meshtastic | 📋 Setup needed | 1-2 hours |
| **Circular** | Debt netting | None | ✅ Complete | 5 minutes |
| **Agents** | AI economy | None | 📋 Spec only | N/A |

---

## Common Use Cases

### For Developers

1. **Learn the Protocol** → Start with `simple/`
2. **Build Offline App** → Study `mesh/`
3. **Implement Netting** → Use `circular/` scripts
4. **Create Agent Economy** → Follow `agents/SPEC.md`

### For Communities

1. **Local Trading Circle** → `circular/` + `mesh/`
2. **Offline Village** → `mesh/` with Meshtastic
3. **Community Currency** → All demos combined

### For Researchers

1. **Credit Expansion** → Study `agents/` spec
2. **Debt Netting** → Analyze `circular/` algorithms
3. **Offline Systems** → Test `mesh/` in field

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    Basis Protocol Layer                      │
├─────────────────────────────────────────────────────────────┤
│  Simple  │   Mesh    │  Circular  │   Agents   │            │
│  Core    │  Offline  │  Netting   │  Economy   │  Demos     │
├──────────┼───────────┼────────────┼────────────┤            │
│          │           │            │            │            │
│  IOU     │  LoRa     │  Debt      │  Agent     │  Features  │
│  Notes   │  Mesh     │  Transfer  │  Wallets   │            │
│          │           │            │            │            │
└──────────┴───────────┴────────────┴────────────┘            │
                              │                                │
                              ▼                                │
                    ┌─────────────────┐                        │
                    │  Tracker Server │                        │
                    │  (Debt Ledger)  │                        │
                    └─────────────────┘                        │
                              │                                │
                              ▼                                │
                    ┌─────────────────┐                        │
                    │  Gateway Node   │                        │
                    │  (Blockchain)   │                        │
                    └─────────────────┘                        │
                              │                                │
                              ▼                                │
                    ┌─────────────────┐                        │
                    │  Ergo Blockchain│                        │
                    │  (Settlement)   │                        │
                    └─────────────────┘                        │
└─────────────────────────────────────────────────────────────┘
```

---

## Getting Started

### Prerequisites

```bash
# Python 3
python3 --version

# For mesh demo only
pip install meshtastic

# For circular demo visualization (optional)
pip install graphviz
```

### Run All Demos (Quick Test)

```bash
# 1. Simple demo (core protocol)
cd demo/basis/simple
cat README.md

# 2. Mesh demo (offline)
cd ../mesh
python3 calculate_netting.py --ledger ../circular/sample_ledger.json

# 3. Circular demo (netting)
cd ../circular
python3 calculate_netting.py --ledger sample_ledger.json

# 4. Agents demo (specification)
cd ../agents
cat README.md
```

---

## Integration Guide

### Combine Mesh + Circular

Use mesh network for offline circular trading:

```bash
# Alice sends IOU via mesh
cd demo/basis/mesh
./send_basis_message.sh alice bob 50000000 "Goods"

# Bob aggregates and nets
cd ../circular
python3 calculate_netting.py --ledger ledger.json

# Settle optimized positions
python3 settle_netting.py --auto
```

### Combine Agents + Mesh

AI agents trading over mesh network:

```bash
# Agent creates IOU
# (implementation pending - see agents/SPEC.md)

# Send via mesh
cd demo/basis/mesh
./send_basis_iou.py --payer repo_agent --payee dev_agent --amount 10000000
```

---

## Testing Checklist

### Basic Testing
- [ ] Simple demo runs successfully
- [ ] Can create and transfer IOU
- [ ] Can redeem IOU against reserve

### Mesh Testing
- [ ] Meshtastic devices configured
- [ ] Can send message over mesh
- [ ] Can receive IOU on device
- [ ] Offline mode works

### Circular Testing
- [ ] Netting calculation correct
- [ ] Debt transfer with consent works
- [ ] Settlement optimization saves fees

### Agent Testing
- [ ] Review SPEC.md
- [ ] Plan implementation
- [ ] Design agent wallet

---

## Resources

### Documentation
- [Whitepaper](../../docs/conf/conf.pdf)
- [Presentation](../../docs/presentation/presentation.md)
- [Ergo Documentation](https://docs.ergoplatform.com/)

### Code
- [ChainCash Protocol](https://github.com/ChainCashLabs/chaincash)
- [Meshtastic Project](https://meshtastic.org/)
- [Ergo AppKit](https://github.com/ergoplatform/ergo-appkit)

### Community
- Telegram: [t.me/chaincashtalks](https://t.me/chaincashtalks)
- Twitter: [@ChainCashLabs](https://twitter.com/ChainCashLabs)

---

## Demo Status Summary

| Demo | Software | Hardware | Docs | Tests | Overall |
|------|----------|----------|------|-------|---------|
| Simple | ✅ | N/A | ✅ | ✅ | **Ready** |
| Mesh | ✅ | 📋 | ✅ | 📋 | **Ready for field** |
| Circular | ✅ | N/A | ✅ | ✅ | **Ready** |
| Agents | 📋 | N/A | ✅ | N/A | **Spec complete** |

**Legend:**
- ✅ Complete
- 📋 Pending/In Progress

---

## Next Steps

### Immediate
1. ✅ Review all demo documentation
2. ✅ Run simple and circular demos
3. 📋 Set up mesh hardware (if testing offline)

### Short Term
1. Test mesh demo with real devices
2. Implement agent wallet (agents/SPEC.md)
3. Combine demos for full scenario

### Long Term
1. Production deployment
2. Mobile app development
3. Real community testing

---

## Support

**Issues?** Check individual demo README files first.

**Questions?** Join Telegram: [t.me/chaincashtalks](https://t.me/chaincashtalks)

**Contributions?** All demos are open source. PRs welcome!

---

**Built by and for the Commons** 🌱

Free, open source community project. No token, no VC, no corporate control.

**P2P Money for Humans & AI Agents** 🤝🤖
