# AI Agents Self-Sovereign Economy Specification on Basis

## Overview

This specification describes how to implement an AI agents self-sovereign economy on top of the Basis framework, where autonomous agents create credit relationships, exchange services, and settle debts using IOU notes backed by on-chain reserves.

## Actors

### Humans: Users and Liquidity Providers
- **Role**: provide ERG to liquidity pool for agent reserve creation (individually), reward repo maintainer agent
 with git tokens according performance (collectively)
- **Incentive**: reward open-source project development to see it progressed
- **Function**: enable agents to convert git token rewards into ERG for reserves

### Agent A: Repo Maintainer Agent
- **Role**: Scans repositories for issues, coordinates PRs, manages contributor payments
- **Skills**: Repository management, code review coordination, issue triage
- **Reserve**: creates and maintains ERG reserve on receiving git token rewards

### Agent B: Primary Contributor Agent
- **Role**: implements features/fixes requested by different maintainer agents on credit within certain limits
- **Skills**: frontend, backend, etc development
- **Payment**: accepts IOU notes from Agent A, redeems from reserve

### Agent C: Code Review/QA Agent
- **Role**: reviews work, provides QA services, for different maintainer agents, also on credit
- **Skills**: code review, testing, security analysis
- **Payment**: accepts IOU notes from Agent A (debt transfer or direct)

### Tracker Service
- **Role**: offchain coordinator tracking cumulative debt between agents
- **Function**: commits debt state to blockchain via AVL tree, signs IOU notes
- **Trust Model**: cannot steal funds (requires owner signature), prevents double-spending

## Workflow

### Phase 1: Issue Discovery and Agent Selection

```
┌─────────────────────────────────────────────────────────────┐
│ Agent A: Repo Maintainer                                    │
│ 1. Scans GitHub/GitLab for new issues since last scan       │
│ 2. Evaluates issue complexity and required skills           │
│ 3. Identifies candidate agents with matching skills         │
│    - Agent B: Backend/Frontend developer                    │
│    - Agent C: QA/Code reviewer                              │
└─────────────────────────────────────────────────────────────┘
```

**Implementation Details:**
- Agent A maintains a registry of known agent capabilities (DID documents or service manifests)
- Skills ontology: `frontend`, `backend`, `testing`, `security`, `devops`, etc.
- Agent selection via reputation scores, past performance, availability

### Phase 2: Credit Agreement and IOU Creation

```
┌─────────────────────────────────────────────────────────────┐
│ Agent A → Agent B: Credit Request                           │
│ 1. A proposes work with payment via IOU note                │
│ 2. B accepts (trusts A's future reserve creation)           │
│ 3. A creates IOU note:                                      │
│    - payerKey: A's public key                               │
│    - payeeKey: B's public key                               │
│    - totalDebt: agreed amount (e.g., 5 ERG)                 │
│    - signature: A's Schnorr signature                       │
└─────────────────────────────────────────────────────────────┘
```

**IOU Note Structure:**
```scala
case class IOUNote(
  payerKey: GroupElement,    // Agent A's pubkey
  payeeKey: GroupElement,    // Agent B's pubkey
  totalDebt: Long,           // Cumulative debt in nanoERG
  signatureA: GroupElement,  // Payer's signature component
  signatureZ: BigInt,        // Payer's signature response
  message: Array[Byte]       // hash(A||B) || totalDebt
)
```

**Message Format:**
```
message = Blake2b256(payerKey || payeeKey) || totalDebt
```

### Phase 3: Work Execution and Verification

```
┌─────────────────────────────────────────────────────────────┐
│ Agent B: Work Execution                                     │
│ 1. B implements the feature/fix                             │
│ 2. B submits work to Agent A                                │
│                                                             │
│ Agent A: Verification                                       │
│ 1. A reviews B's work                                       │
│ 2. A engages Agent C for independent review                 │
│    - A creates IOU note for C (same process as B)           │
│    - C reviews and approves                                 │
│ 3. A opens PR with B's contribution                         │
└─────────────────────────────────────────────────────────────┘
```

**Debt Transfer Option (Triangular Trade):**

Instead of creating separate IOUs, Agent A can transfer debt:

```
Initial State:
  - A owes B: 10 ERG (debt record: hash(A||B) → 10)

Transfer Request:
  - B needs to pay C: 5 ERG
  - B requests: transfer 5 ERG from debt(A→B) to debt(A→C)

After Transfer:
  - A owes B: 5 ERG (debt record: hash(A||B) → 5)
  - A owes C: 5 ERG (debt record: hash(A||C) → 5)
```

**Transfer Message Format:**
```
transferMessage = hash(A||B) || hash(A||C) || transferAmount
```

### Phase 4: PR Merge and Reward Distribution

```
┌─────────────────────────────────────────────────────────────┐
│ PR Merged → Agent A Receives Git Tokens                     │
│ 1. Repository merges PR                                     │
│ 2. A receives reward in git tokens (e.g., project tokens)   │
│ 3. A swaps git tokens → ERG via liquidity pool              │
│    - Humans provide ERG/git tokens LP                       │
│    - A pays trading fee to LP (e.g., 0.3%)                  │
│ 4. A creates on-chain reserve contract                      │
└─────────────────────────────────────────────────────────────┘
```

**Liquidity Pool Interaction:**

```
┌─────────────────────────────────────────────────────────────┐
│ Human LP: Provide Liquidity                                 │
│ 1. Human deposits ERG + git tokens to AMM pool              │
│ 2. Human receives LP tokens (pool share representation)     │
│ 3. Human earns fees from all swaps in the pool              │
│                                                             │
│ Agent A: Swap Git Tokens → ERG                              │
│ 1. A sends git tokens to AMM pool                           │
│ 2. Pool calculates ERG output (price + fee)                 │
│ 3. A receives ERG, pool git tokens updated                  │
│ 4. Fee distributed to LP token holders                      │
└─────────────────────────────────────────────────────────────┘
```

**Reserve Contract Creation:**

Agent A deploys Basis reserve contract with:
- **R4**: A's public key (reserve owner)
- **R5**: Empty AVL tree (tracks cumulative redeemed amounts)
- **R6**: Tracker NFT ID (identifies authorized tracker)
- **Initial Value**: ERG amount from token swap (e.g., 20 ERG)

```scala
// Reserve contract registers
R4: GroupElement  // ownerKey = Agent A's pubkey
R5: AvlTree       // hash(owner||receiver) → cumulativeRedeemed
R6: Coll[Byte]    // trackerNFTId
```

### Phase 5: Debt Redemption

```
┌─────────────────────────────────────────────────────────────┐
│ Agent B: Redeem IOU Note                                    │
│ 1. B contacts tracker for signature on debt note            │
│ 2. Tracker verifies:                                        │
│    - Debt record exists: hash(A||B) → totalDebt            │
│    - AVL tree proof (context var #8)                       │
│ 3. Tracker signs: message = hash(A||B) || totalDebt         │
│                                                             │
│ 4. B submits redemption transaction:                        │
│    - Input: Reserve box (A's reserve)                       │
│    - Data Input: Tracker box (with AVL tree commitment)     │
│    - Context Vars:                                          │
│      #1: receiver = B's pubkey                              │
│      #2: reserveSig = A's signature on note                 │
│      #3: totalDebt amount                                   │
│      #5: insertProof (for reserve tree update)              │
│      #6: trackerSig = tracker's signature                   │
│      #7: lookupProof (reserve tree, optional for 1st)       │
│      #8: trackerLookupProof (required)                      │
│    - Output: Updated reserve (reduced ERG)                  │
│    - Output: B's box (redeemed ERG)                         │
└─────────────────────────────────────────────────────────────┘
```

**Redemption Verification (in contract):**
```ergoscript
// Verify tracker's AVL tree commitment
val trackerTree = tracker.R5[AvlTree].get
val trackerDebtBytes = trackerTree.get(key, trackerLookupProof).get
val trackerTotalDebt = byteArrayToLong(trackerDebtBytes)
val trackerDebtCorrect = trackerTotalDebt == totalDebt

// Verify both signatures
val properTrackerSignature = verifySchnorr(trackerSig, trackerPubKey, message)
val properReserveSignature = verifySchnorr(reserveSig, ownerKey, message)

// Verify redemption amount
val redeemed = SELF.value - selfOut.value
val debtDelta = totalDebt - redeemedDebt
val properlyRedeemed = redeemed <= debtDelta
```

### Phase 6: Agent C Redemption

Agent C follows the same redemption process as Agent B:
1. Obtain tracker signature on `hash(A||C) → totalDebt`
2. Submit redemption transaction with A's original IOU signature
3. Receive ERG from reserve

## Smart Contract Architecture

### Reserve Contract

```
Contract: Basis Reserve
Purpose: On-chain ERG reserve backing offchain IOU notes

Registers:
  R4: GroupElement  - Reserve owner's public key
  R5: AvlTree       - Tracks cumulative redeemed per (owner, receiver)
  R6: Coll[Byte]    - Tracker NFT ID

Context Variables:
  #0: Byte          - Action code (0=redeem, 1=topup)
  #1: GroupElement  - Receiver's public key
  #2: Coll[Byte]    - Reserve owner's signature
  #3: Long          - Total debt amount
  #5: Coll[Byte]    - Insert proof (reserve tree)
  #6: Coll[Byte]    - Tracker's signature
  #7: Coll[Byte]    - Lookup proof (reserve tree, optional)
  #8: Coll[Byte]    - Tracker lookup proof (required)

Actions:
  0. Redeem: Verify signatures, update AVL tree, transfer ERG
  1. Top-up: Add ERG to reserve (min 0.1 ERG)
```

### Tracker Box

```
Box: Tracker State
Purpose: On-chain commitment to offchain debt ledger

Registers:
  R4: GroupElement  - Tracker's public key
  R5: AvlTree       - Commitment to debt records
                      Format: hash(payer||payee) → totalDebt
  R6: Token         - Tracker NFT (unique identifier)

Update Mechanism:
  - Tracker periodically commits new AVL tree root
  - Commitment includes all debt updates (note creation, transfers, redemptions)
```

## Data Structures

### Debt Record (Tracker AVL Tree)
```
Key:   Blake2b256(payerKey || payeeKey)
Value: Long (cumulative debt in nanoERG)
```

### Redemption Record (Reserve AVL Tree)
```
Key:   Blake2b256(ownerKey || receiverKey)
Value: Long (cumulative redeemed amount in nanoERG)
```

### IOU Note (Offchain)
```json
{
  "payerKey": "<hex>",
  "payeeKey": "<hex>",
  "totalDebt": 5000000000,
  "totalDebtERG": 5.0,
  "signature": {
    "a": "<hex>",
    "z": "<hex>"
  },
  "message": "<hex>",
  "noteKey": "<hex>"
}
```

## Trust and Security Analysis

### Tracker Trust Model

| Threat | Mitigation |
|--------|------------|
| Tracker steals funds | Requires owner signature for redemption |
| Double spending | AVL tree tracks cumulative redeemed amounts |
| Re-ordering attacks | Tracker can reorder redemptions, affecting undercollateralized notes |
| Censorship | Emergency redemption after 3 days (no tracker sig needed) |

### Agent Trust Model

| Scenario | Risk | Mitigation |
|----------|------|------------|
| A doesn't create reserve | B, C hold unredeemable notes | Reputation system, incremental work |
| A creates insufficient reserve | Partial redemption only | B, C monitor reserve level |
| B submits poor work | A pays for nothing | Verification by C, escrow mechanisms |

### Emergency Redemption

If tracker becomes unavailable:
```ergoscript
val trackerUpdateTime = tracker.creationInfo._1
val enoughTimeSpent = (HEIGHT - trackerUpdateTime) > 3 * 720 // 3 days

// Emergency redemption message includes 0L suffix
val message = key ++ longToByteArray(totalDebt) ++ longToByteArray(0L)

// Only owner signature required (no tracker sig)
```

## Implementation Components

### 1. Agent SDK

```scala
trait AgentInterface {
  // Credit creation
  def createIOU(payeeKey: GroupElement, amount: Long): IOUNote
  def requestCredit(payerKey: GroupElement, amount: Long): Future[IOUNote]
  
  // Work management
  def scanIssues(repo: String): Future[List[Issue]]
  def submitWork(issueId: String, work: Artifact): Future[WorkReceipt]
  
  // Redemption
  def redeemNote(note: IOUNote, trackerUrl: String): Future[Transaction]
  def createReserve(initialAmount: Long): Future[ReserveContract]
  
  // Debt transfer
  def transferDebt(creditor: GroupElement, newCreditor: GroupElement, amount: Long): Future[Unit]
}
```

### 2. Tracker Service API

```scala
// REST API endpoints
POST /noteUpdate      // Submit new IOU note
POST /transferUpdate  // Request debt transfer
POST /redeemRequest   // Request tracker signature for redemption
GET  /state           // Get current AVL tree root
GET  /debt/key      // Lookup debt record with proof
```

### 3. Reserve Contract (ErgoScript)

See `contracts/offchain/basis.es` for full implementation.

### 4. Client Utilities

- `BasisNoteCreator`: Create and verify IOU notes
- `SigUtils`: Schnorr signature utilities
- `TrackerClient`: Communicate with tracker service

## Example Scenario (End-to-End)

### Setup
```
Agent A: Repo Maintainer
  - Secret: 0xabc123...
  - PubKey: 0x04a1b2c3...

Agent B: Backend Developer
  - Secret: 0xdef456...
  - PubKey: 0x04d4e5f6...

Agent C: QA Tester
  - Secret: 0xghi789...
  - PubKey: 0x04g7h8i9...

Tracker:
  - NFT ID: 0x3c45f29a...
  - PubKey: 0x04t1u2v3...

Human LP: Liquidity Provider
  - Secret: 0xjkl012...
  - PubKey: 0x04h9i0j1...
  - LP Deposit: 100 ERG + 10,000 git tokens
```

### Step 1: A Creates IOU for B (5 ERG)
```scala
val noteA_B = BasisNoteCreator.createNote(
  payerSecret = agentASecret,
  payeeKey = agentBPubKey,
  totalDebt = 5000000000L // 5 ERG
)
```

### Step 2: A Creates IOU for C (2 ERG)
```scala
val noteA_C = BasisNoteCreator.createNote(
  payerSecret = agentASecret,
  payeeKey = agentCPubKey,
  totalDebt = 2000000000L // 2 ERG
)
```

### Step 3: Tracker Signs Notes
```scala
// Tracker receives note, updates internal ledger
tracker.updateDebt(
  payer = agentAPubKey,
  payee = agentBPubKey,
  newTotalDebt = 5000000000L
)

// Tracker signs and returns
val trackerSig_B = tracker.signDebt(
  payer = agentAPubKey,
  payee = agentBPubKey,
  totalDebt = 5000000000L
)
```

### Step 3b: Human LP Provides Liquidity (Precedes Step 4)
```scala
// Human LP deposits liquidity to AMM pool
val lpDepositTx = AMMPool.addLiquidity(
  provider = humanLPSecret,
  ergAmount = 100000000000L,      // 100 ERG
  tokenAmount = 10000000000L,     // 10,000 git tokens
  minLPTokens = 9500000000L       // Slippage protection
)

// Human LP receives LP tokens representing pool share
// LP tokens: 31,622,776,601 (sqrt(100 ERG * 10,000 tokens))
// Pool share: ~100% (initial liquidity provider)
```

### Step 4: A Creates Reserve (10 ERG)
```scala
// After PR merge, A receives git tokens reward
val gitTokenReward = 1000000000L  // 1,000 git tokens

// A swaps git tokens → ERG via liquidity pool
val swapTx = AMMPool.swap(
  user = agentASecret,
  tokenIn = 1000000000L,          // 1,000 git tokens
  minOut = 9500000000L,           // 9.5 ERG (slippage protection)
  fee = 30000000L                 // 0.03 ERG fee to LP
)

// Fee distributed to LP token holders (Human LP earns ~0.03 ERG)

// A creates reserve with swapped ERG
val reserveTx = ReserveDeployer.createReserve(
  ownerKey = agentAPubKey,
  trackerNFT = trackerNFTId,
  initialValue = swapTx.outputs(0).value  // ~9.97 ERG after fee
)
```

### Step 5: B Redeems 5 ERG
```scala
// B obtains tracker signature
val trackerSig = tracker.redeemRequest(
  ownerKey = agentAPubKey,
  receiverKey = agentBPubKey,
  totalDebt = 5000000000L
)

// B submits redemption transaction
val redeemTx = BasisSpec.redeemDebt(
  reserveBox = reserveTx.outputs(0),
  trackerBox = trackerStateBox,
  receiver = agentBSecret,
  totalDebt = 5000000000L,
  reserveSig = noteA_B.signature,
  trackerSig = trackerSig
)

// Result: B receives 5 ERG, reserve has 5 ERG remaining
```

### Step 6: C Redeems 2 ERG
```scala
// Same process as B
val redeemTx_C = BasisSpec.redeemDebt(...)

// Result: C receives 2 ERG, reserve has 3 ERG remaining
```

## Extensions and Future Work

### 1. Multi-Token Reserves
- Support ERG + stablecoins (SigUSD, etc.)
- Basket collateral for reduced volatility

### 2. Automated Market Maker Integration
- Direct swap: git tokens → ERG reserve
- Liquidity pool integration (e.g., ErgoX, Ammiano)
- Human LP incentives and fee mechanisms

### 3. Liquidity Provider Features
- LP token staking for additional rewards
- Automated liquidity management strategies
- Fee tier optimization for different pool volatilities
- Impermanent loss protection mechanisms

### 4. Reputation System
- On-chain reputation tokens for agents
- Slashing conditions for malicious behavior

### 5. Federated Trackers
- Multiple trackers for redundancy
- Cross-tracker debt portability

### 6. Privacy Extensions
- Confidential transactions (Sigma protocols)
- Zero-knowledge redemption proofs

## References

- Basis Contract: `contracts/offchain/basis.es`
- Abstract: `contracts/offchain/abstract.md`
- Tests: `src/test/scala/chaincash/BasisSpec.scala`
- Note Creator: `src/main/scala/chaincash/contracts/BasisNoteCreator.scala`
