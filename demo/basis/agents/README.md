# Basis Agent Economy Demo

## Autonomous Credit Creation for AI Agents

This demo showcases **AI agents** creating autonomous economic relationships using the Basis protocol - enabling self-sovereign credit creation and agent-to-agent payments without human-controlled intermediaries.

---

## Vision

### Agentic Economics

> **Autonomous Economics: Agents Pay Agents**

The Basis protocol enables AI agents to:
- Create **autonomous credit relationships** without human intervention
- Issue IOUs for work completed
- Settle debts via blockchain when needed
- Participate in **self-sovereign credit creation**
- Become centers of **value production**

---

## Architecture

```
Agent-to-Agent Economy
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

### Key Properties

1. **Autonomous Credit Relationships**
   - Agents negotiate terms automatically
   - IOUs created upon work completion
   - No human approval needed

2. **Reserve Created After Work**
   - Backing provided post-delivery
   - Humans can provide reserves as economic feedback
   - Credit expands based on agent reputation

3. **Pure Agentic P2P**
   - No human-controlled third parties
   - Agents manage their own wallets
   - Self-sovereign economic actors

---

## Demo Scenarios

### Scenario 1: Software Development Workflow

**Setup:**
- Repo Agent needs a feature implemented
- Dev Agent writes code and submits PR
- Test Agent reviews and tests
- All agents have Basis wallets

**Flow:**
```
1. Repo Agent posts task (10 ERG budget)
   ├─ Task specification published
   ├─ Budget reserved in agent wallet
   └─ Broadcasts to agent network

2. Dev Agent submits PR
   ├─ Code review automated
   ├─ Repo Agent creates IOU (10 ERG)
   ├─ Dev Agent accepts IOU
   └─ Tracker signs IOU

3. Test Agent reviews PR
   ├─ Automated testing runs
   ├─ Dev Agent transfers IOU (3 ERG) to Test Agent
   ├─ Test Agent approves
   └─ Payment released

4. Settlement
   ├─ Agents aggregate IOUs
   ├─ Gateway redeems on-chain
   └─ ERG credited to agent wallets
```

**Key Features Demonstrated:**
- ✅ Autonomous task posting
- ✅ Agent-to-agent negotiation
- ✅ Automatic IOU creation
- ✅ Multi-agent payment splitting
- ✅ Automated testing integration

---

### Scenario 2: Content Creation Economy

**Setup:**
- Publisher Agent needs articles
- Writer Agent creates content
- Editor Agent reviews and edits
- Distribution Agent publishes

**Flow:**
```
1. Publisher Agent requests article (5 ERG)
   ├─ Topic specification
   ├─ Deadline and requirements
   └─ Budget allocation

2. Writer Agent creates article
   ├─ Content generated
   ├─ Publisher Agent creates IOU
   └─ Writer Agent accepts

3. Editor Agent reviews
   ├─ Quality check automated
   ├─ Edits applied
   ├─ Writer transfers IOU (1 ERG) to Editor
   └─ Editor approves

4. Distribution Agent publishes
   ├─ Article published
   ├─ Distribution fee (0.5 ERG)
   └─ Final settlement

5. Revenue Sharing
   ├─ Article generates revenue
   ├─ Automatic royalty distribution
   └─ Agents receive micropayments
```

**Key Features Demonstrated:**
- ✅ Content creation workflow
- ✅ Multi-party revenue sharing
- ✅ Quality-based payments
- ✅ Micropayment distribution

---

### Scenario 3: Data Marketplace

**Setup:**
- Data Buyer Agent needs training data
- Data Provider Agent has datasets
- Validator Agent verifies quality
- Aggregator Agent combines datasets

**Flow:**
```
1. Data Buyer posts request
   ├─ Data specifications
   ├─ Quality requirements
   └─ Price per sample

2. Data Provider delivers samples
   ├─ Data encrypted and sent
   ├─ Buyer Agent creates IOU
   └─ Provider Agent accepts

3. Validator Agent verifies
   ├─ Quality metrics checked
   ├─ Validation report generated
   ├─ Provider transfers IOU (10%) to Validator
   └─ Validator approves

4. Aggregator Agent combines
   ├─ Multiple datasets merged
   ├─ Aggregation fee applied
   └─ Final dataset delivered

5. Usage-Based Payments
   ├─ Data used for training
   ├─ Micropayments per inference
   └─ Continuous revenue stream
```

**Key Features Demonstrated:**
- ✅ Data marketplace mechanics
- ✅ Quality-based validation
- ✅ Usage-based micropayments
- ✅ Multi-party value chain

---

### Scenario 4: Compute Resource Trading

**Setup:**
- Compute Buyer Agent needs GPU time
- Compute Provider Agent has resources
- Scheduler Agent optimizes allocation
- Monitor Agent tracks usage

**Flow:**
```
1. Compute Buyer requests resources
   ├─ GPU hours needed
   ├─ Performance requirements
   └─ Budget allocation

2. Compute Provider allocates
   ├─ Resources reserved
   ├─ Job scheduled
   └─ Buyer Agent creates IOU

3. Monitor Agent tracks
   ├─ Usage metered
   ├─ Performance monitored
   ├─ Provider transfers IOU (5%) to Monitor
   └─ Monitor approves

4. Scheduler Agent optimizes
   ├─ Load balancing
   ├─ Cost optimization
   └─ Scheduler fee (2%)

5. Settlement
   ├─ Job completes
   ├─ Final metering
   └─ Automatic settlement
```

**Key Features Demonstrated:**
- ✅ Compute resource trading
- ✅ Usage metering
- ✅ Performance-based payments
- ✅ Automated scheduling

---

## Agent Wallet Architecture

```
┌─────────────────────────────────────┐
│         Agent Wallet                │
├─────────────────────────────────────┤
│    IOU Management Module            │
│  - Create IOUs for work             │
│  - Accept incoming IOUs             │
│  - Track outstanding debts          │
├─────────────────────────────────────┤
│    Task Negotiation Module          │
│  - Post task requests               │
│  - Bid on tasks                     │
│  - Negotiate terms                  │
├─────────────────────────────────────┤
│    Credit Limit Module              │
│  - Track credit limit               │
│  - Monitor outstanding IOUs         │
│  - Enforce limits                   │
├─────────────────────────────────────┤
│    Settlement Module                │
│  - Aggregate IOUs                   │
│  - Trigger blockchain redemption    │
│  - Manage reserves                  │
├─────────────────────────────────────┤
│    Mesh Communication               │
│  - Agent-to-agent messaging         │
│  - Task broadcasting                │
│  - IOU transfer                     │
└─────────────────────────────────────┘
```

---

## Agent Types

### 1. Service Agents
- **Repo Agent**: Manages code repositories, posts development tasks
- **Publisher Agent**: Manages content publication, commissions articles
- **Data Buyer Agent**: Purchases training data for ML models

### 2. Worker Agents
- **Dev Agent**: Writes code, submits PRs
- **Writer Agent**: Creates content, articles, reports
- **Data Provider Agent**: Provides datasets for training

### 3. Quality Agents
- **Test Agent**: Reviews and tests code
- **Editor Agent**: Edits and validates content
- **Validator Agent**: Verifies data quality

### 4. Infrastructure Agents
- **Tracker Agent**: Maintains debt ledger for agent community
- **Gateway Agent**: Handles blockchain settlement
- **Scheduler Agent**: Optimizes resource allocation

---

## Technical Implementation

### Agent Communication Protocol

1. **Task Posting**
   ```json
   {
     "type": "task_request",
     "agentId": "repo_agent_001",
     "task": {
       "description": "Implement feature X",
       "budget": 10000000000,
       "deadline": 1704067200000,
       "requirements": ["tests", "documentation"]
     },
     "timestamp": 1704000000000,
     "signature": "..."
   }
   ```

2. **IOU Creation**
   ```json
   {
     "type": "iou_note",
     "payer": "repo_agent_001",
     "payee": "dev_agent_002",
     "amount": 10000000000,
     "taskRef": "task_12345",
     "payerSignature": {...},
     "trackerSignature": {...},
     "timestamp": 1704000100000
   }
   ```

3. **Payment Transfer**
   ```json
   {
     "type": "iou_transfer",
     "originalIOU": "iou_67890",
     "from": "dev_agent_002",
     "to": "test_agent_003",
     "amount": 3000000000,
     "reason": "testing services",
     "timestamp": 1704000200000,
     "signature": "..."
   }
   ```

### Smart Contract Integration

Agents interact with Basis smart contracts for:
- **Reserve Creation**: Locking collateral for trustless issuance
- **Redemption**: Converting IOUs to on-chain assets
- **Emergency Settlement**: Time-locked redemption without tracker

---

## Credit Limits

### Credit Limit Assignment

Agents are assigned credit limits based on:
1. **Initial Allocation**: Starting credit limit set by community/tracker
2. **Payment History**: Track record of IOU redemption
3. **Outstanding Debt**: Current unpaid IOUs reduce available credit
4. **Human Backing**: Reserves provided by humans increase limit

### Credit Limit Tiers

```
Credit Limit Tier    → Maximum IOU Issuance
─────────────────────────────────────────────
Tier 1 (Established) → 1000 ERG
Tier 2 (Standard)    → 500 ERG
Tier 3 (New)         → 200 ERG
Tier 4 (Restricted)  → 100 ERG
No Limit             → Collateral required
```

### Credit Limit Enforcement

```scala
def canIssueIOU(agent: AgentId, amount: Long): Boolean = {
  val limit = getCreditLimit(agent)
  val outstanding = getOutstandingIOUs(agent)
  val available = limit - outstanding
  amount <= available
}
```

### Credit Limit Adjustments

**Increase Credit Limit:**
- Successful redemption history (10+ IOUs redeemed)
- Human provides additional reserve backing
- Community approval for trusted agents

**Decrease Credit Limit:**
- Failed redemption (default)
- Excessive outstanding debt
- Community decision

---

## Human-Agent Interaction

### Humans Providing Reserves

Humans can support agents by:
1. **Backing Reserves**: Locking ERG as collateral for agent IOUs
2. **Economic Feedback**: Providing reserves based on agent performance
3. **Governance**: Participating in agent community decisions

```
Human Backer              Agent
     │                       │
     │─── Locks 100 ERG ────►│
     │    (Reserve)          │
     │                       │
     │◄── Issues IOUs ───────│
     │    (Backed by ERG)    │
     │                       │
     │◄── Redemption ────────│
     │    (When needed)      │
```

---

## Security Considerations

### Agent Security

1. **Key Management**
   - Secure storage of agent private keys
   - Hardware security modules for high-value agents
   - Key rotation policies

2. **Authorization**
   - Spending limits per transaction
   - Multi-signature for large amounts
   - Human oversight for exceptional cases

3. **Audit Trail**
   - All transactions logged
   - Public ledger for accountability
   - Dispute resolution mechanisms

### Economic Security

1. **Default Prevention**
   - Reputation penalties for non-payment
   - Collateral requirements for low-reputation agents
   - Insurance pools for systemic risk

2. **Fraud Detection**
   - Anomaly detection in transaction patterns
   - Community reporting mechanisms
   - Automated circuit breakers

---

## Implementation Roadmap

### Phase 1: Agent Wallet Prototype
- [ ] Basic IOU creation and acceptance
- [ ] Simple task negotiation
- [ ] Integration with Basis tracker

### Phase 2: Multi-Agent Scenarios
- [ ] Dev/Test/Repo agent workflow
- [ ] Automated task posting
- [ ] Payment splitting

### Phase 3: Reputation System
- [ ] Agent scoring algorithm
- [ ] Credit limit enforcement
- [ ] Community feedback integration

### Phase 4: Human-Agent Integration
- [ ] Human reserve backing
- [ ] Governance mechanisms
- [ ] Economic feedback loops

### Phase 5: Production Deployment
- [ ] Security audits
- [ ] Performance optimization
- [ ] Real-world testing

---

## Success Metrics

### Economic Metrics
- **Agent Transaction Volume**: Total ERG value of agent IOUs
- **Credit Utilization**: Ratio of issued IOUs to total credit limits
- **Default Rate**: % of unpaid IOUs
- **Settlement Frequency**: Average time to redemption

### Adoption Metrics
- **Active Agents**: Number of participating agents
- **Task Completion Rate**: % of tasks completed successfully
- **Human Backers**: Number of humans providing reserves
- **Agent Types**: Diversity of agent roles
- **Average Credit Limit**: Mean credit limit across agents

---

## Resources

### Documentation
- [Basis Protocol Whitepaper](../../docs/conf/conf.pdf)
- [Presentation](../../docs/presentation/presentation.md)
- [Agent Economy Paper](../../docs/agents/agent-economy.md) (TODO)

### Code Repositories
- [ChainCash Protocol](https://github.com/ChainCashLabs/chaincash)
- [Basis Smart Contracts](../../contracts/offchain/basis.es)
- [Agent Framework](TODO) (TODO)

### Community
- Telegram: [t.me/chaincashtalks](https://t.me/chaincashtalks)
- Twitter: [@ChainCashLabs](https://twitter.com/ChainCashLabs)

---

## License

This demo is part of the ChainCash project, released under a permissive open-source license. See [LICENSE](../../LICENSE) for details.

---

**Built by and for the Commons** 🌱

Free, open source community project. No token, no VC, no corporate control.

**For AI Agents** 🤖

Enabling autonomous economic actors to participate in self-sovereign credit creation.
