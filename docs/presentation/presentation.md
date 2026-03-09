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

## Basis on Ergo: P2P Money for Humans & AI Agents

---

## The Vision

### P2P Money for the Digital World

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

### Human-to-Human

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
                               │
                               ▼
                          ┌─────────────┐
                          │ Test Agent  │
                          │ (reviews)   │
                          └─────────────┘
```


---

## P2P Interactions

### Micropayments for Content

```
Pay-per-article without subscriptions
• Publisher accepts reader's IOU
• Small amounts, no on-chain fees
• Redeem aggregated debt later
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

- **UTXO model**: perfect for off-chain interactions
- **Smart contracts**: Expressive redemption logic
- **Trust-minimized De-Fi Stack** to continue on-chain
- **PoW security**

---


## Monetary Expansion

### Reserve Collateral Options


* ERG (native)     
* Bitcoin (via bridges/wrapping)          
* Tokenized gold, silver, commodities      
* Stablecoins                              
* Basket tokens, and so on                 


---

## Impact on Ergo Ecosystem

### Boosting DeFi & ERG Demand

**Direct Benefits**
- **ERG as min-trust collateral** — reserves locked in contracts
- **Increased on-chain activity**
- **TVL growth** — more value secured on Ergo
```
More users → More reserves → More ERG demand → Higher security
     ↓                                              ↑
     └────────────── Positive feedback ─────────────┘
```

---

## Why This Matters

### For Humans
- **Free banking for everyone**
- **Works offline**: trade without Internet (over Mesh etc)
- **No forced collateralization** when trust is enough

---

### For AI Agents
- **Autonomous economics**: agents pay agents
- **Agentic economics**: via self-sovereign credit creation, agents can become in the center of value production
- **No human-controlled third-parties**: pure agentic P2P

---

### For the World
- **Alternative to political money**: self-sovereign grassroots issuance
- **Local credit, global settlement**: best of both worlds
- **Elastic money supply**: expands with trust when possible
- **Individual risk**: the system does not force to accept debt, it is individual choice of every user

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
- **Consulting & support** — help communities deploy
- **Protocol fee** - default option for tracker 
- **Extend the protocol** — grants, bounties, donations

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