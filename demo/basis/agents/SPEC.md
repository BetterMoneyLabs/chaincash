# Agent Economy Specification

## Agentic Credit Creation on Basis Protocol

This specification defines the implementation requirements for enabling AI agents to create autonomous credit relationships using the Basis protocol.

---

## 1. Overview

### 1.1 Purpose

Enable AI agents to:
- Create and manage IOUs autonomously
- Negotiate task terms without human intervention
- Settle debts via blockchain when needed
- Build reputation through economic activity
- Participate in self-sovereign credit creation

### 1.2 Scope

This specification covers:
- Agent wallet architecture
- Agent communication protocol
- Task negotiation workflow
- IOU creation and management
- Reputation system
- Human-agent interaction

### 1.3 References

- [Basis Protocol Whitepaper](../../docs/conf/conf.pdf)
- [Basis Smart Contract](../../contracts/offchain/basis.es)
- [Presentation](../../docs/presentation/presentation.md)

---

## 2. Agent Wallet

### 2.1 Requirements

**R-2.1.1** Agent wallet MUST support:
- IOU creation with automatic signature
- IOU acceptance and verification
- Tracker signature requests
- Balance tracking (assets, liabilities, net worth)

**R-2.1.2** Agent wallet MUST implement:
- Secure key storage
- Transaction signing
- Backup and recovery
- Multi-signature support for large amounts

### 2.2 Interface

```scala
trait AgentWallet {
  // Create IOU for work received
  def createIOU(
    payee: AgentId,
    amount: Long,
    taskRef: TaskId,
    message: String
  ): IOUNote
  
  // Accept incoming IOU
  def acceptIOU(iou: IOUNote): Boolean
  
  // Transfer IOU to another agent
  def transferIOU(
    iou: IOUNote,
    to: AgentId,
    amount: Long,
    reason: String
  ): IOUNote
  
  // Get current balance
  def getBalance: AgentBalance
  
  // Request tracker signature
  def requestTrackerSignature(iou: IOUNote): IOUNote
}

case class AgentBalance(
  assets: Long,        // IOUs held
  liabilities: Long,   // IOUs issued
  netWorth: Long,      // assets - liabilities
  availableCredit: Long
)
```

### 2.3 Key Management

**R-2.3.1** Private keys MUST be stored securely:
- Hardware Security Module (HSM) for production
- Encrypted key store for development
- Never logged or transmitted

**R-2.3.2** Key rotation policy:
- Rotate keys every 90 days
- Graceful transition period
- Notify counterparties

---

## 3. Agent Communication Protocol

### 3.1 Message Types

**M-3.1.1** Task Request
```json
{
  "type": "task_request",
  "version": "1.0",
  "sender": "repo_agent_001",
  "task": {
    "id": "task_12345",
    "description": "Implement feature X",
    "category": "software_development",
    "budget": 10000000000,
    "currency": "nanoERG",
    "deadline": 1704067200000,
    "requirements": ["tests", "documentation", "code_review"],
    "deliverables": ["pull_request", "test_results"]
  },
  "terms": {
    "paymentOnCompletion": true,
    "partialPaymentAllowed": false,
    "collateralRequired": false
  },
  "timestamp": 1704000000000,
  "signature": "..."
}
```

**M-3.1.2** Task Bid
```json
{
  "type": "task_bid",
  "version": "1.0",
  "sender": "dev_agent_002",
  "taskId": "task_12345",
  "bid": {
    "amount": 10000000000,
    "estimatedCompletion": 1704050000000,
    "qualifications": ["scala_expert", "ergo_experience"],
    "portfolio": ["previous_work_1", "previous_work_2"]
  },
  "timestamp": 1704001000000,
  "signature": "..."
}
```

**M-3.1.3** Task Award
```json
{
  "type": "task_award",
  "version": "1.0",
  "sender": "repo_agent_001",
  "taskId": "task_12345",
  "awardedTo": "dev_agent_002",
  "finalTerms": {
    "amount": 10000000000,
    "deadline": 1704050000000,
    "milestones": [
      {"name": "design", "amount": 2000000000},
      {"name": "implementation", "amount": 6000000000},
      {"name": "testing", "amount": 2000000000}
    ]
  },
  "timestamp": 1704002000000,
  "signature": "..."
}
```

**M-3.1.4** IOU Note
```json
{
  "type": "iou_note",
  "version": "1.0",
  "payer": "repo_agent_001",
  "payee": "dev_agent_002",
  "amount": 10000000000,
  "currency": "nanoERG",
  "taskRef": "task_12345",
  "message": "Payment for feature X implementation",
  "payerSignature": {
    "a": "...",
    "z": "..."
  },
  "trackerSignature": {
    "a": "...",
    "z": "..."
  },
  "timestamp": 1704003000000,
  "expiry": 1735539000000
}
```

**M-3.1.5** IOU Transfer
```json
{
  "type": "iou_transfer",
  "version": "1.0",
  "originalIOU": "iou_67890",
  "from": "dev_agent_002",
  "to": "test_agent_003",
  "amount": 3000000000,
  "reason": "Testing services for task_12345",
  "endorsement": {
    "message": "...",
    "signature": "..."
  },
  "timestamp": 1704004000000
}
```

### 3.2 Communication Channels

**R-3.2.1** Agents MUST support:
- Direct messaging (HTTP/gRPC)
- Mesh network broadcasting
- Email fallback for critical messages
- SMS for urgent notifications

**R-3.2.2** Message delivery guarantees:
- At-least-once delivery
- Idempotency for duplicate detection
- Acknowledgment receipts
- Retry with exponential backoff

---

## 4. Task Negotiation Workflow

### 4.1 State Machine

```
Task States:
  POSTED → BIDDING → AWARDED → IN_PROGRESS → 
  SUBMITTED → REVIEWING → COMPLETED → SETTLED
  
  Any state → CANCELLED (with penalty if after AWARDED)
```

### 4.2 Workflow Steps

**Step 1: Task Posting**
```
Repo Agent:
  1. Define task requirements
  2. Set budget and deadline
  3. Broadcast to agent network
  4. Wait for bids
```

**Step 2: Bidding**
```
Dev Agent:
  1. Discover task posting
  2. Evaluate requirements
  3. Submit bid with qualifications
  4. Negotiate terms if needed
```

**Step 3: Award**
```
Repo Agent:
  1. Evaluate bids
  2. Select winner
  3. Send task award
  4. Reserve budget
```

**Step 4: Execution**
```
Dev Agent:
  1. Complete work
  2. Submit deliverables
  3. Request payment
  4. Receive IOU
```

**Step 5: Settlement**
```
Both Agents:
  1. Track IOU until redemption
  2. Aggregate for batch settlement
  3. Redeem via blockchain
  4. Update balances
```

---

## 5. IOU Management

### 5.1 IOU Lifecycle

```
CREATED → SIGNED → ACCEPTED → [TRANSFERRED*] → REDEEMED
                              ↘ CANCELLED
```

### 5.2 IOU Validation

**R-5.2.1** Before accepting IOU, agent MUST verify:
- Payer signature is valid
- Tracker signature is valid
- Amount matches agreed terms
- Task reference is valid
- Expiry is acceptable

**R-5.2.2** Validation algorithm:
```scala
def validateIOU(iou: IOUNote): Boolean = {
  val payerSigValid = verifySignature(
    iou.message, 
    iou.payer, 
    iou.payerSignature
  )
  
  val trackerSigValid = verifySignature(
    iou.message, 
    trackerPublicKey, 
    iou.trackerSignature
  )
  
  val notExpired = iou.expiry > System.currentTimeMillis()
  
  val sufficientCredit = getPayerCredit(iou.payer) >= iou.amount
  
  payerSigValid && trackerSigValid && notExpired && sufficientCredit
}
```

### 5.3 IOU Transfer

**R-5.3.1** IOU transfer requires:
- Original payer consent (for large amounts)
- Endorsement from transferor
- Tracker notification
- Updated ledger entry

**R-5.3.2** Partial transfers allowed:
- Split IOU into multiple smaller IOUs
- Each with own endorsement chain
- Original terms preserved

---

## 6. Credit Limits

### 6.1 Credit Limit Assignment

**R-6.1.1** Each agent MUST have a credit limit:
- Set by tracker/community on agent registration
- Can be increased by human reserve backing
- Adjusted based on payment history

**R-6.1.2** Credit limit tiers:
```scala
case class CreditLimitTier(
  name: String,
  limit: Long,
  requirements: List[String]
)

val tiers = Seq(
  CreditLimitTier("Established", 1000000000000L, List("10+ redemptions", "no defaults")),
  CreditLimitTier("Standard", 500000000000L, List("5+ redemptions")),
  CreditLimitTier("New", 200000000000L, List("initial allocation")),
  CreditLimitTier("Restricted", 100000000000L, List("limited history"))
)
```

### 6.2 Credit Limit Enforcement

**R-6.2.1** Before issuing IOU, agent MUST check:
```scala
def canIssueIOU(agent: AgentId, amount: Long): Boolean = {
  val limit = getCreditLimit(agent)
  val outstanding = getOutstandingIOUs(agent)
  val available = limit - outstanding
  amount <= available
}
```

**R-6.2.2** IOU issuance rejected if:
- Amount exceeds available credit
- Agent has defaulted IOUs
- Agent is suspended

### 6.3 Credit Limit Adjustments

**R-6.3.1** Automatic increases:
- +10% after 10 successful redemptions
- +50% with human reserve backing (1:1 collateral)
- +100% with 2:1 collateral backing

**R-6.3.2** Automatic decreases:
- -50% on first default
- -100% (suspended) on second default
- -25% if outstanding > 80% of limit for 30 days

### 6.4 Available Credit Calculation

**R-6.4.1** Available credit computed as:
```scala
case class AgentCredit(
  totalLimit: Long,        // Assigned credit limit
  outstanding: Long,       // Unredeemed IOUs
  reserved: Long,          // Reserved for pending tasks
  available: Long          // Can issue: totalLimit - outstanding - reserved
)

def calculateAgentCredit(agent: AgentId): AgentCredit = {
  val limit = getCreditLimit(agent)
  val outstanding = getOutstandingIOUs(agent)
  val reserved = getReservedCredit(agent)
  val available = limit - outstanding - reserved
  
  AgentCredit(limit, outstanding, reserved, available)
}
```

---

## 7. Human-Agent Interaction

### 7.1 Human Backing

**R-7.1.1** Humans can back agents by:
- Creating reserve with agent as beneficiary
- Setting collateral ratio requirements
- Defining automatic top-up rules
- Monitoring agent activity

**R-7.1.2** Reserve creation:
```scala
def createAgentReserve(
  humanSecret: BigInt,
  agentPublicKey: GroupElement,
  collateralAmount: Long,
  autoTopUp: Boolean,
  minCollateralRatio: Double
): ReserveBox = {
  // Create reserve box with agent public key
  // Set up automatic top-up if balance falls below threshold
  // Monitor and alert on unusual activity
}
```

### 7.2 Governance

**R-7.2.1** Human governance mechanisms:
- Vote on agent community rules
- Approve large credit extensions
- Resolve disputes
- Set fee structures

**R-7.2.2** Governance voting:
```json
{
  "type": "governance_proposal",
  "proposer": "human_backer_001",
  "proposal": {
    "title": "Increase credit limit for dev_agent_002",
    "description": "Agent has excellent track record",
    "currentLimit": 500000000000L,
    "proposedLimit": 1000000000000L
  },
  "votingPeriod": 604800000,
  "quorum": 0.5,
  "threshold": 0.66,
  "timestamp": 1704000000000
}
```

---

## 8. Security Requirements

### 8.1 Authentication

**S-8.1.1** All messages MUST be signed:
- Sender's private key
- Timestamp to prevent replay
- Message hash included in signature

**S-8.1.2** Signature verification:
```scala
def verifyMessage(msg: AgentMessage): Boolean = {
  val expectedHash = blake2b256(msg.payload + msg.timestamp)
  verifySignature(expectedHash, msg.sender, msg.signature)
}
```

### 8.2 Authorization

**S-8.2.1** Spending limits enforced:
- Per-transaction limit
- Daily limit
- Counterparty limit
- Requires human approval above thresholds

**S-8.2.2** Multi-signature for large amounts:
```scala
def requiresMultiSig(amount: Long): Boolean = {
  amount > 100000000000L  // 100 ERG
}

def getRequiredSigners(amount: Long): Int = {
  if (amount > 1000000000000L) 3  // 1000 ERG
  else if (amount > 500000000000L) 2  // 500 ERG
  else 1
}
```

### 8.3 Audit Trail

**S-8.3.1** All transactions logged:
- Message sent/received
- IOU created/transferred/redeemed
- Balance changes
- Reputation updates

**S-8.3.2** Logs immutable:
- Append-only storage
- Cryptographic hashing
- Regular backups
- Tamper detection

---

## 9. Implementation Phases

### Phase 1: Core Wallet (4 weeks)
- [ ] Basic IOU creation
- [ ] Signature verification
- [ ] Balance tracking
- [ ] Key management

### Phase 2: Communication (4 weeks)
- [ ] Message protocol
- [ ] Task negotiation
- [ ] Agent discovery
- [ ] Mesh networking

### Phase 3: Credit Limits (4 weeks)
- [ ] Credit limit assignment
- [ ] Available credit calculation
- [ ] Limit enforcement
- [ ] Adjustment rules

### Phase 4: Integration (4 weeks)
- [ ] Tracker integration
- [ ] Blockchain settlement
- [ ] Human backing
- [ ] Governance

### Phase 5: Production (4 weeks)
- [ ] Security audit
- [ ] Performance testing
- [ ] Documentation
- [ ] Deployment

---

## 10. Testing Requirements

### 10.1 Unit Tests
- IOU creation and validation
- Signature verification
- Balance calculations
- Reputation scoring

### 10.2 Integration Tests
- Multi-agent workflows
- Task negotiation
- Payment splitting
- Settlement

### 10.3 Security Tests
- Key management
- Signature forgery attempts
- Replay attacks
- Authorization bypass

### 10.4 Performance Tests
- Transaction throughput
- Message latency
- Scalability (1000+ agents)
- Resource usage

---

## 11. Success Criteria

### 11.1 Functional
- [ ] Agents can create and accept IOUs autonomously
- [ ] Task negotiation completes without human intervention
- [ ] Multi-agent payment splitting works correctly
- [ ] Credit limits enforced correctly
- [ ] Available credit calculated accurately

### 11.2 Security
- [ ] No unauthorized transactions
- [ ] All messages properly authenticated
- [ ] Keys securely stored
- [ ] Audit trail complete and immutable

### 11.3 Performance
- [ ] Transaction latency < 1 second
- [ ] Support 1000+ concurrent agents
- [ ] Settlement completes within 10 minutes
- [ ] Memory usage < 100MB per agent

### 11.4 Credit Management
- [ ] Credit limits enforced in real-time
- [ ] Available credit updates instantly
- [ ] Limit adjustments applied correctly
- [ ] No overdrafts permitted

---

## 12. Glossary

| Term | Definition |
|------|------------|
| Agent | Autonomous software actor participating in economy |
| IOU | Promise to pay, created as Basis note |
| Tracker | Maintains debt ledger for agent community |
| Reserve | Collateral backing agent IOUs |
| Credit Limit | Maximum IOU amount agent can issue |
| Available Credit | Credit limit minus outstanding IOUs |
| Outstanding IOUs | Unredeemed IOUs issued by agent |
| Settlement | Converting IOUs to on-chain assets |
| Default | Failure to redeem IOU when presented |

---

## Appendix A: Example Agent Interaction

```
1. [POST] Repo Agent → Network: Task Request
   {task: "Implement feature", budget: 10 ERG}

2. [BID] Dev Agent → Repo Agent: Task Bid
   {amount: 10 ERG, timeline: 2 days}

3. [AWARD] Repo Agent → Dev Agent: Task Award
   {accepted, deadline: 48h}

4. [WORK] Dev Agent: Implements feature

5. [SUBMIT] Dev Agent → Repo Agent: Deliverables
   {code, tests, docs}

6. [IOU] Repo Agent → Dev Agent: IOU Note
   {amount: 10 ERG, tracker_signed: true}

7. [TRANSFER] Dev Agent → Test Agent: IOU Transfer
   {amount: 3 ERG, reason: "testing"}

8. [REDEEM] Test Agent → Blockchain: Redemption
   {IOUs: [3 ERG], on_chain: true}
```

---

**Version:** 1.0  
**Status:** Draft  
**Last Updated:** 2026-03-29
