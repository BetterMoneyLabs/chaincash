---
marp: true
theme: gaia
class: lead
size: 16:9
paginate: true
backgroundColor: #1a1a2e
color: #f0f0f0
style: |
  section {
    background: #1a1a2e;
    color: #f0f0f0;
    font-family: 'Inter', 'Segoe UI', sans-serif;
    font-size: 22px;
  }
  h1, h2, h3, h4, h5, h6 {
    color: #f0f0f0;
    font-weight: 700;
  }
  h1 { font-size: 1.7em; }
  h2 { font-size: 1.4em; }
  h3 { font-size: 1.15em; color: #f0f0f0; }
  strong, b { color: #ffffff; }
  em, i { color: #ffcc66; }
  code {
    color: #ff9999;
    background: #16213e;
    padding: 2px 6px;
    border-radius: 3px;
  }
  pre {
    background: #0f0f23;
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
    border-left: 4px solid #ff9933;
    color: #ffddaa;
    background: #0f0f23;
    padding: 8px 16px;
    margin: 12px 0;
  }
  ul li::marker { color: #ff9933; }
  a { color: #ffaa66; }
  img { background: transparent; }
---

# Local Credit, Global Settlement
## Enhancing Trust-Based Credit Creation 
##   with On-Chain Reserve Contracts

**Alexander Chepurnoy**
*Ergo*

8th CCCS Conference — RAMICS
*Plurality of Social Currencies: solidarity economy, municipal and community currencies for inclusive and sustainable futures*
Rio de Janeiro, Brazil, June 8–12, 2026

---

## The Need for Monetary Expansion

### Blockchain Assets Are Inelastic

- Bitcoin, Ergo, and similar assets have **algorithmically predetermined emission schedules**
- Supply **cannot expand or contract** in response to real-world economic demand
- Entirely **disconnected** from productive activity, trade volumes, or credit needs

---

## ChainCash: On-Chain Notes with Collective Backing

- Peer-to-peer money creation through trust and/or collateral
- Notes backed by **all previous spenders** in the chain
- Redemption against **any reserve** in spending history,
    with re-redemption later possible against an earlier one
- Fully on-chain — **transparency and censorship resistance**

### Limitation

- Every transaction recorded on the blockchain
- Scalability and cost issues for frequent use
- Hard to apply in **community currency** setting with many small payments

---

## Offchain Payments Today: No Monetary Expansion

### Existing Bitcoin Layer-2 Systems

| System | Mechanism | Collateral Requirement |
|--------|-----------|------------------------|
| **Lightning Network** | Bidirectional payment channels | **100% locked on-chain** |
| **Cashu** | Chaumian blind signatures (e-cash) | **100% mint reserves** |
| **Fedimint** | Federated threshold custody | **100% federation backing** |


---

## The Problem: No Credit Expansion

- Supply is capped by locked collateral
- Cannot respond to growing economic activity

### Contrast with Community Currencies

- Community currencies (LETS, Time Banks, WIR) thrive on **trust-based credit**
- Existing digital systems **discard this principle** by enforcing full collateralization
- **Trust within communities becomes an underutilized resource**

---

## Basis: A Protocol to unify Community Credit and Blockchain Assets

- **Local trust-based IOUs** circulate within communities
- **On-chain reserves** serve as optional backing
- Individual IOU note acceptance 
- **Off-chain payments** — low fees, no blockchain bottleneck
- **Tracker service** coordinates debt state transparently

```
┌──────────┐      IOU Note      ┌──────────┐
│  Payer   │ ─────────────────► │  Payee   │
│ (Issuer) │   (signed)         │(Receiver)│
└────┬─────┘                    └────┬─────┘
     │                               │
     └──────► Tracker (witness) ◄────┘
              (commits state to chain)
```

---

## Note Creation: Issuing Community Credit

1. **Payer** (e.g., a community member) creates an IOU note:
   - Payer's **signature**
   - Payee's **public key**
   - **Debt amount**
   - **Timestamp**
   - Optional: **reserve contract** for extra backing

2. **Payee** verifies payer's signature and note contents
   - **Acceptance is always voluntary** — each participant decides whom to trust
   - Sends to tracker

3. **Tracker signature** is obtained later
   - Required only for **redemption** against a reserve contract
   - Certifies the debt was recorded in tracker's authenticated state

---

## Debt Redemption

### Standard Redemption (Trust + Collateral)

- Payee presents the **witnessed IOU** to the **reserve contract**
- Contract verifies payer's signature, tracker's signature, debt amount
- Redeemed amount is **released to payee** by the reserve contract

### Emergency Exit (Tracker Unavailable)

- After **3 days (2,160 blocks)**, tracker signature becomes **optional**
- Redeem using payer's signature + last committed tracker state
- **Replay attacks prevented** by timestamp verification


---

## Debt Transfer: Circulating Credit Within Communities

### Triangular Trade (No Blockchain Needed)

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
```

- **B** requests **A** to co-sign:
  - Reduced debt A→B
  - New debt A→C
- Tracker witnesses both; old note replaced
- **A now owes C directly**

---

## Tracker State: Transparent Debt Registry

### AVL Tree of Debt Relationships

$$\text{hash}(\text{payerKey} \,||\, \text{payeeKey}) \rightarrow (\text{totalDebt}, \text{timestamp})$$

- Cryptographically strong hash function
- Efficient lookups and **proof generation**
- Root digest **committed to blockchain periodically**
- Enables **emergency redemption** and **public verification**

### Governance Options

| Design | Trust Model | Best For |
|--------|-------------|----------|
| Single Server | Trusted operator | Small communities |
| Federation (MuSig2) | Threshold of signers | Municipal currencies |
| Sidechain (PoW) | Permissionless consensus | Regional / multi-community |

---

## Scenario 1: Rural Community Trading

### Disconnected Village with Occasional Connectivity

```
Village (no direct Internet)
┌──────┐    ┌──────┐    ┌──────┐
│ Alice│◄──►│  Bob │◄──►│ Carol│
└──────┘    └──────┘    └──────┘
      ╲         │          ╱
       ╲        │         ╱
        └───────┴────────┘
            Local Tracker
       (syncs via satellite/cellular)
```

- Trade on **credit** within community over **Bluetooth / LoRa**
- Tracker commits to blockchain **when Internet available**
- Redemption possible via **slow links (SMS, email)**
- **No forced collateralization** — trust is enough locally

---

## Scenario 2: Municipal Currency with Gradual Backing

### A City-Scale Complementary Currency

1. **Phase 1 — Pure Trust**: Local businesses issue IOUs to each other
   - No collateral required
   - Tracker (city federation) witnesses transactions

2. **Phase 2 — Selective Backing**: As trade expands to outsiders
   - Businesses optionally create **on-chain reserves**
   - Reserves back IOUs for **inter-city / low-trust** transactions

3. **Phase 3 — Hybrid Economy**: Local trust dominates internally, reserves handle external settlement

---

## Security & Trust Minimization

### Tracker Cannot Steal Funds

- Tracker only **certifies inclusion** in state
- **Issuer's signature always required** for redemption
- Tracker cannot unilaterally create or redeem notes

### Anti-Censorship

- Pseudonymity allows picking new keys
- Reserve contract has **refund option** (long timeout)
- Future: blind signatures + zero-knowledge proofs

### Emergency Exit

- After timeout: tracker signature **optional**
- Same message format: `key || totalDebt || timestamp`
- Reserve owner's signature still required

---

## Implementation

- Ergo blockchain as it has all the needed functionalities and superior for offchain protocols
- Supports ERG as well as custom tokens (stablecoins etc) for reserves
- Offchain debt tracker

---


## Resources

- Paper & code: `github.com/ChainCashLabs/chaincash`
- Whitepaper: `https://github.com/BetterMoneyLabs/chaincash/blob/master/docs/basis/basis.pdf`
- Tracker: `https://github.com/BetterMoneyLabs/basis-tracker`
- Everything is open sourced and under public domain
- Contributions are much needed

---

## Thank You

**Alexander Chepurnoy**
*Ergo*
`kushti@protonmail.ch`

**github.com/ChainCashLabs/chaincash**

8th CCCS Conference — RAMICS
*Plurality of Social Currencies*
Rio de Janeiro, June 2026
