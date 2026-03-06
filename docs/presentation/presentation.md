---
marp: true
theme: gaia
class: lead
size: 16:9
paginate: true
backgroundColor: #000000
color: #ffffff
style: |
  section {
    background: #000000;
    color: #ffffff;
    font-family: 'Inter', 'Segoe UI', sans-serif;
    font-size: 20px;
  }
  h1, h2, h3, h4, h5, h6 {
    color: #ffffff;
    font-weight: 700;
  }
  h1 { font-size: 1.8em; }
  h2 { font-size: 1.5em; }
  h3 { font-size: 1.2em; color: #ffffff; }
  strong, b { color: #ffffff; }
  em, i { color: #ff9999; }
  code {
    color: #ff8888;
    background: #1a1a1a;
    padding: 2px 6px;
    border-radius: 3px;
  }
  pre {
    background: #0a0a0a;
    border: 1px solid #333;
    border-radius: 6px;
    padding: 12px;
  }
  pre code {
    background: transparent;
    padding: 0;
    color: #ffaaaa;
  }
  blockquote {
    border-left: 4px solid #ff3333;
    color: #ffcccc;
    background: #0a0a0a;
    padding: 8px 16px;
    margin: 12px 0;
  }
  ul li::marker { color: #ff3333; }
  a { color: #ff6666; }
  img { background: transparent; }
---

## Basis on Ergo: P2P Money for Humans & Agents

---

## The Vision

### P2P Money for a Connected World

> **Local trust, global settlement**

### Goals
- Humans trading in **local communities** (possibly over mesh)
- **AI agents** creating autonomous economic relationships
- **Optional collateral** via blockchain when trust is insufficient

### Today's State
- Lightning/Cashu require **100% collateral** → no credit
- Blockchain fees too high for **micropayments**
- No solutions for **occasionally-connected** areas

---

## P2P Interactions

### Human-to-Human (Mesh Networks)

- Credit-based trading within community
- Backed by blockchain assets when there is no trust
- Blockchain sync when connected
- Redemption possible via slow links (SMS, email)
- Tracker can be changed freely, it can't steal 

```
Disconnected Village
┌──────┐    ┌──────┐    ┌──────┐
│ Alice│◄──►│  Bob │◄──►│ Carol│
└──────┘    └──────┘    └──────┘
     ╲         │          ╱
      ╲        │         ╱
       └───────┴────────┘
           Local Tracker
      (syncs when Internet available)
```

---

## P2P Interactions

### Agent-to-Agent (Autonomous Economy)

- Autonomous credit relationships
- Reserve created after work completes
- Humans providing economic feedback by providing reserves (backing)

```
┌─────────────┐      IOU      ┌─────────────┐
│ Repo Agent  │──────────────►│ Dev Agent   │
│ (needs code)│  "10 ERG debt"│ (writes PR) │
└─────────────┘               └─────────────┘
       ▲                              │
       │                              ▼
       │                         ┌─────────────┐
       └─────────────────────────│ Test Agent  │
            IOU "5 ERG debt"     │ (reviews)   │
                                 └─────────────┘
```


---

## P2P Interactions

### Triangular Trade (Debt Transfer)

```
Before:                    After Transfer:
┌─────┐ owes 10 ┌─────┐   ┌─────┐ owes 5  ┌─────┐
│  A  │────────►│  B  │   │  A  │────────►│  B  │
└─────┘         └─────┘   └─────┘         └─────┘
                           │ owes 5
                           ▼
                        ┌─────┐
                        │  C  │
                        └─────┘

B buys from C → A's debt transfers (with A's consent)
No on-chain redemption needed!
```

---

## Why Ergo?

- **UTXO model**: Perfect for tokenized collateral
- **Smart contracts**: Expressive redemption logic
- **Low fees**: Viable for small settlements
- **PoW security**: No trusted validators

### Emergency Exit
- Tracker offline > 3 days? → **Emergency redemption**
- Protects against tracker failure/censorship

---

### Minimal Trust Collateral

```
┌─────────────────────────────────────┐
│         Ergo Blockchain             │
│  ┌───────────────────────────────┐  │
│  │  Reserve Contract (Smart)     │  │
│  │  • ERG locked securely        │  │
│  │  • Redemption rules enforced  │  │
│  │  • No trusted third party     │  │
│  └───────────────────────────────┘  │
└─────────────────────────────────────┘
          ▲              ▲
          │              │
    Optional Collateral  │
    (when trust low)     │
                         │
              Tracker cannot steal funds
              (needs owner signature)
```

---

## Monetary Expansion Beyond ERG

### Tokenized Assets & Cross-Chain

```
┌─────────────────────────────────────────────┐
│         Reserve Collateral Options          │
├─────────────────────────────────────────────┤
│  • ERG (native)                             │
│  • Tokenized gold, silver, commodities      │
│  • Bitcoin (via bridges/wrapping)           │
│  • Stablecoins                              │
│  • LP tokens from DEXs                      │
│  • Real estate tokens                       │
└─────────────────────────────────────────────┘
```

### How It Works
- **Any Ergo token** can back IOU notes
- **Cross-chain assets** (BTC, ETH) via bridges
- **Diversified reserves**: Mix of assets reduces risk

---

## Example: Local Currency Expansion

```
Gold mining co-op issues IOUs
→ Backed by tokenized gold reserves
→ IOUs circulate locally on trust
→ Redeemable for gold-backed ERG tokens
```

**Monetary expansion**: Credit created against real assets

---

## Impact on Ergo Ecosystem

### Boosting DeFi & ERG Demand

**Direct Benefits**
- **ERG as primary collateral** — reserves locked in contracts
- **Increased on-chain activity** — settlements, redemptions
- **Fee burn/rewards** — network fees paid in ERG
- **TVL growth** — more value secured on Ergo

---

## Impact on Ergo Ecosystem

### Ecosystem Synergies

- **DEX integration** — IOU/ERG trading pairs
- **Stablecoin demand** — SigUSD and other Ergo assets
- **Cross-chain bridges** — BTC, ETH flow through Ergo
- **Developer activity** — new contracts, tools, services

**Network Effects**
```
More users → More reserves → More ERG demand → Higher security
     ↓                                              ↑
     └────────────── Positive feedback ─────────────┘
```

---

## Real-World Scenarios

### 1. Rural Community Trading
```
Village in Ghana (occasional Internet)
• Local tracker on Raspberry Pi
• Farmers trade on credit daily
• Sync to Ergo weekly via satellite
• Redeem against ERG reserves when needed
```

---

### 2. AI Agent Marketplace
```
Autonomous agents on the internet
• Agent A hires B for work (IOU note)
• B subcontracts to C (debt transfer)
• Payment in git tokens → convert to ERG
• Create reserve, agents redeem
```

---

### 3. Micropayments for Content
```
Pay-per-article without subscriptions
• Reader accepts publisher's IOU
• Small amounts, no on-chain fees
• Aggregate and redeem later
```

---

## Why This Matters

### For Humans
- **Financial sovereignty**: Be your own bank
- **Works offline**: Trade without Internet
- **No forced collateralization**: Trust is enough

---

### For AI Agents
- **Autonomous economics**: Agents pay agents
- **No KYC/centralized services**: Pure P2P
- **Programmable money**: Smart contracts enforce rules

---

### For the World
- **Alternative to political money**: Self-sovereign issuance
- **Local credit, global settlement**: Best of both worlds
- **Elastic money supply**: Expands with trust, contracts when needed

---

## Open Source Community

### Built by and for the Commons

- No token
- **Free, open source** community project
- **Permissive license** — use, modify, deploy freely
- No venture capital, no corporate control
- Developed transparently on GitHub
- Contributions welcome from all

---

## Monetization Possibilities

### For Operators & Entrepreneurs

- **Run a tracker node** — earn fees on settlements
- **Issue backed IOUs** — create local credit systems
- **Liquidity provision** — earn from reserve management
- **Gateway services** — on/off-ramp for cash ↔ crypto
- **Custom deployments** — white-label for communities

### For Developers
- **Build integrations** — wallets, exchanges, tools
- **Consulting & support** — help communities deploy
- **Extend the protocol** — grants, bounties, donations

### Sustainable & Decentralized
- No rent extraction by platform owners
- Value flows to network participants
- Community-governed fee markets

---

## Current Status

### ✅ Working
- Reserve contract on Ergo
- Tracker prototype
- P2P payment flows tested
- Emergency redemption tested

---

### 🚧 Building
- Rust implementation (production server)
- Mesh network demos
- Agent economy simulations

---

### 📚 Resources
- Whitepaper: `github.com/ChainCashLabs/chaincash/docs/conf/conf.pdf`
- Code: `github.com/ChainCashLabs/chaincash`
- Chat: `t.me/chaincashtalks`