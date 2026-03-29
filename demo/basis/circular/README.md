# Circular Trading Demo - Triangular Debt Transfer

## Efficient Multi-Party Trading with Debt Transfer

This demo showcases **triangular trade** and **circular debt cancellation** - powerful features of the Basis protocol that enable efficient multi-party trading without on-chain settlement.

---

## The Vision

### Triangular Trade (Debt Transfer)

From the [presentation](../../docs/presentation/presentation.md):

```
Before:                    After Transfer:
┌─────┐ owes 10 ┌─────┐   ┌─────┐ owes 5  ┌─────┐
│  A  │────────►│  B  │   │  A  │────────►│  B  │
└─────┘         └─────┘   └─────┘         └─────┘
     ╲                       ╲
      ╲ owes 5                 ╲
       ▼                         ▼
    ┌─────┐                   ┌─────┐
    │  C  │                   │  C  │
    └─────┘                   └─────┘

Scenario: B buys from C for 5
Solution: A's debt transfers to C (with A's consent)
Result: No on-chain redemption needed!
```

---

## Why Circular Trading Matters

### The Problem: Inefficient Bilateral Settlement

Without triangular trade:
```
A owes B: 10 ERG
B owes C: 5 ERG
C owes A: 3 ERG

Naive settlement:
1. A pays B: 10 ERG (on-chain transaction)
2. B pays C: 5 ERG (on-chain transaction)
3. C pays A: 3 ERG (on-chain transaction)

Total: 3 on-chain transactions, high fees
```

### The Solution: Netting + Debt Transfer

With triangular trade:
```
Net positions:
A: -10 + 3 = -7 ERG (owes 7)
B: +10 - 5 = +5 ERG (owed 5)
C: +5 - 3 = +2 ERG (owed 2)

Optimized settlement:
1. A pays B: 5 ERG (debt transfer)
2. A pays C: 2 ERG (debt transfer)

Total: 0 on-chain transactions, minimal fees
```

---

## Demo Scenarios

### Scenario 1: Simple Triangular Trade

**Participants:** Alice (A), Bob (B), Carol (C)

**Initial State:**
```
Alice owes Bob: 10 ERG
```

**Event:** Bob buys goods from Carol for 5 ERG

**Without Triangular Trade:**
```
Bob would pay Carol 5 ERG from his own funds
Alice still owes Bob 10 ERG
Total debt in system: 15 ERG
```

**With Triangular Trade:**
```
Alice's debt to Bob is transferred to Carol
Alice now owes Carol: 5 ERG
Alice still owes Bob: 5 ERG
Bob's debt to Carol: 0 ERG (paid via debt transfer)
Total debt in system: 10 ERG (reduced!)
```

**Benefits:**
- ✅ Bob doesn't need to spend his own funds
- ✅ Carol gets paid by Alice directly
- ✅ No on-chain transaction needed
- ✅ System-wide debt reduced

---

### Scenario 2: Circular Debt Cancellation

**Participants:** Alice, Bob, Carol, Dave

**Initial State:**
```
Alice → Bob: 10 ERG
Bob → Carol: 8 ERG
Carol → Dave: 6 ERG
Dave → Alice: 4 ERG
```

**Visual:**
```
     10 ERG
Alice ─────► Bob
  ▲           │
  │           │ 8 ERG
  │           ▼
Dave ◄────── Carol
  ▲           │
  │           │ 6 ERG
  └───────────┘
    4 ERG
```

**Net Positions:**
```
Alice: -10 + 4 = -6 ERG (owes 6)
Bob: +10 - 8 = +2 ERG (owed 2)
Carol: +8 - 6 = +2 ERG (owed 2)
Dave: +6 - 4 = +2 ERG (owed 2)
```

**After Netting:**
```
Alice pays Bob: 2 ERG
Alice pays Carol: 2 ERG
Alice pays Dave: 2 ERG

Total transactions: 3 (instead of 4)
Total value moved: 6 ERG (instead of 28 ERG)
Efficiency gain: 78% reduction!
```

---

### Scenario 3: Community Trading Circle

**Participants:** 5 community members in a village

**Setup:**
```
Members: Alice, Bob, Carol, Dave, Eve
All trade with each other over one month
All IOUs tracked by local tracker
```

**Monthly Ledger:**
```
         │  Owes  │ Owed   │ Net
─────────┼────────┼────────┼────────
Alice    │  100   │   80   │ -20
Bob      │   60   │   90   │ +30
Carol    │   80   │   70   │ -10
Dave     │   90   │   80   │ -10
Eve      │   70   │   80   │ +10
─────────┴────────┴────────┴────────
```

**Without Netting:**
- Total IOUs: 500 ERG
- Settlement transactions: 5+ on-chain
- Fees: ~5 ERG (at 1 ERG per tx)

**With Netting:**
- Net debt: 40 ERG
- Settlement transactions: 3 on-chain
- Fees: ~3 ERG
- **Savings: 40% reduction**

---

## Implementation

### Debt Transfer Message Format

```json
{
  "type": "debt_transfer",
  "version": "1.0",
  "original_debtor": "alice",
  "original_creditor": "bob",
  "new_creditor": "carol",
  "amount": 50000000,
  "currency": "nanoERG",
  "reason": "Bob buys from Carol, transfers Alice's debt",
  "consent": {
    "debtor_signed": true,
    "debtor_signature": "...",
    "timestamp": 1704000000000
  },
  "original_iou_ref": "iou_12345"
}
```

### Netting Calculation Algorithm

```python
def calculate_net_positions(transactions):
    """
    Calculate net positions for circular trading.
    
    Args:
        transactions: List of (debtor, creditor, amount) tuples
    
    Returns:
        Dict of {participant: net_position}
        Positive = owed money, Negative = owes money
    """
    positions = {}
    
    for debtor, creditor, amount in transactions:
        # Debtor owes (negative)
        positions[debtor] = positions.get(debtor, 0) - amount
        # Creditor is owed (positive)
        positions[creditor] = positions.get(creditor, 0) + amount
    
    return positions

def optimize_settlement(positions):
    """
    Optimize settlement to minimize transactions.
    
    Returns:
        List of (payer, payee, amount) for settlement
    """
    debtors = [(p, -amt) for p, amt in positions.items() if amt < 0]
    creditors = [(p, amt) for p, amt in positions.items() if amt > 0]
    
    settlements = []
    
    while debtors and creditors:
        debtor, debt_amt = debtors.pop()
        creditor, credit_amt = creditors.pop()
        
        # Settle minimum of debt and credit
        amount = min(debt_amt, credit_amt)
        settlements.append((debtor, creditor, amount))
        
        # Put back remainder
        if debt_amt > amount:
            debtors.append((debtor, debt_amt - amount))
        if credit_amt > amount:
            creditors.append((creditor, credit_amt - amount))
    
    return settlements

# Example usage
transactions = [
    ("alice", "bob", 10),
    ("bob", "carol", 5),
    ("carol", "alice", 3)
]

positions = calculate_net_positions(transactions)
# Result: {'alice': -7, 'bob': 5, 'carol': 2}

settlements = optimize_settlement(positions)
# Result: [('alice', 'bob', 5), ('alice', 'carol', 2)]
```

---

## Running the Demo

### Step 1: Setup Initial IOUs

```bash
# Alice creates IOU to Bob (10 ERG)
cd /path/to/chaincash/demo/circular
./setup_iou.sh alice bob 100000000 "Initial debt"

# Bob creates IOU to Carol (5 ERG)
./setup_iou.sh bob carol 50000000 "Goods purchase"

# Carol creates IOU to Alice (3 ERG)
./setup_iou.sh carol alice 30000000 "Service payment"
```

### Step 2: Calculate Net Positions

```bash
# Run netting calculation
python3 calculate_netting.py --ledger ledger.json

# Output:
# ==================================================
#   Circular Trading - Net Positions
# ==================================================
#   Alice: -7.000000000 ERG (owes)
#   Bob:   +5.000000000 ERG (owed)
#   Carol: +2.000000000 ERG (owed)
# ==================================================
#   Total debt before: 18.000000000 ERG
#   Total debt after:   7.000000000 ERG
#   Reduction: 61.1%
# ==================================================
```

### Step 3: Execute Debt Transfer

```bash
# Transfer Alice's debt from Bob to Carol
./transfer_debt.sh --debtor alice --from bob --to carol --amount 50000000

# Output:
# ==================================================
#   Debt Transfer Executed
# ==================================================
#   Original: Alice owes Bob 5 ERG
#   New:      Alice owes Carol 5 ERG
#   Reason:   Bob buys from Carol
#   Consent:  Alice signed ✓
# ==================================================
```

### Step 4: Settle Optimized Positions

```bash
# Execute optimized settlement
python3 settle_netting.py --positions netting_result.json

# Output:
# ==================================================
#   Settlement Plan
# ==================================================
#   Alice → Bob:   5.000000000 ERG
#   Alice → Carol: 2.000000000 ERG
# ==================================================
#   Total transactions: 2
#   Total value: 7.000000000 ERG
#   On-chain fees saved: ~8 ERG (vs 3 transactions)
# ==================================================
```

---

## Scripts Provided

### `calculate_netting.py`

Calculates net positions from ledger.

```bash
python3 calculate_netting.py --ledger ledger.json
python3 calculate_netting.py --transactions "alice,bob,10;bob,carol,5"
```

### `transfer_debt.sh`

Executes debt transfer with consent.

```bash
./transfer_debt.sh --debtor alice --from bob --to carol --amount 50000000
```

### `settle_netting.py`

Executes optimized settlement.

```bash
python3 settle_netting.py --positions netting_result.json
python3 settle_netting.py --auto  # Auto-execute settlements
```

### `visualize_circle.py`

Creates visual representation of trading circle.

```bash
python3 visualize_circle.py --ledger ledger.json --output circle.png
```

---

## Consent Mechanism

### Why Consent is Required

Debt transfer requires the **debtor's consent** because:
1. Changes who the debtor owes
2. May affect debtor's relationship with new creditor
3. Prevents unauthorized debt shuffling

### Consent Flow

```
1. Bob proposes transfer to Carol
   └─> Message: "Transfer 5 ERG debt from Alice to you"

2. Carol accepts
   └─> Message: "I accept debt from Alice"

3. Alice consents (REQUIRED)
   └─> Signs: "I agree to owe Carol instead of Bob"

4. Tracker executes transfer
   └─> Ledger updated: Alice→Carol 5 ERG
```

### Consent Message Format

```json
{
  "type": "debt_transfer_consent",
  "debtor": "alice",
  "transfer_ref": "transfer_12345",
  "consent": true,
  "signature": "...",
  "timestamp": 1704000000000
}
```

---

## Real-World Use Cases

### 1. Village Trading Circle

**Scenario:** 10 families trade goods/services monthly

**Without Circular Trading:**
- Each family settles individually
- 20+ on-chain transactions
- High fees eat into small margins

**With Circular Trading:**
- Monthly netting calculation
- 3-5 optimized settlements
- **60-75% fee reduction**

### 2. Supply Chain Finance

**Scenario:** Manufacturer → Distributor → Retailer

```
Manufacturer owes Supplier: 1000 ERG
Distributor owes Manufacturer: 800 ERG
Retailer owes Distributor: 600 ERG

Net positions:
Manufacturer: -200 ERG
Distributor:  -200 ERG
Retailer:     +600 ERG
Supplier:     +1000 ERG

Optimized:
Retailer → Supplier: 600 ERG
Retailer → Manufacturer: 200 ERG
Retailer → Distributor: 200 ERG
```

### 3. Freelancer Collective

**Scenario:** 5 freelancers share clients and referrals

```
Freelancer A refers to B: 5 ERG commission
Freelancer B refers to C: 3 ERG commission
Freelancer C refers to D: 4 ERG commission
Freelancer D refers to E: 6 ERG commission
Freelancer E refers to A: 2 ERG commission

Net positions:
A: -3 ERG
B: +2 ERG
C: +1 ERG
D: +2 ERG
E: -4 ERG

Settlement:
E → A: 3 ERG
E → D: 1 ERG
(Instead of 5 separate payments)
```

---

## Benefits Summary

### Economic Benefits

| Metric | Without Circular | With Circular | Improvement |
|--------|-----------------|---------------|-------------|
| Transactions | N | ~N/3 | 66% reduction |
| On-chain fees | High | Low | 60-75% savings |
| Capital efficiency | Poor | Good | Less locked capital |
| Settlement time | Slow | Fast | Netting is instant |

### Social Benefits

- **Strengthens community ties** - Circular trading encourages local commerce
- **Reduces dependency on external liquidity** - Internal debt circulation
- **Enables micro-trading** - Small transactions become viable
- **Builds trust networks** - Multi-party relationships

### Technical Benefits

- **Scalability** - O(N) transactions become O(1) netting
- **Privacy** - Net positions reveal less than full transaction history
- **Flexibility** - Debt can be transferred with consent
- **Resilience** - System works even with intermittent connectivity

---

## Security Considerations

### 1. Consent Verification

```python
def verify_consent(transfer, debtor_public_key):
    """Verify debtor actually consented to transfer."""
    message = hash_transfer_details(transfer)
    return verify_signature(message, debtor_public_key, transfer.consent.signature)
```

### 2. Double-Spending Prevention

```python
def check_debt_not_already_transferred(transfer, ledger):
    """Ensure same debt isn't transferred twice."""
    iou_ref = transfer.original_iou_ref
    existing_transfers = ledger.get_transfers_for_iou(iou_ref)
    return len(existing_transfers) == 0
```

### 3. Tracker Cannot Steal

```
Tracker can:
✓ Calculate net positions
✓ Propose debt transfers
✓ Execute with proper consent

Tracker cannot:
✗ Transfer debt without debtor consent
✗ Create fake IOUs
✗ Steal funds (requires reserve owner signature)
```

---

## Testing Checklist

### Unit Tests
- [ ] Net position calculation correct
- [ ] Debt transfer with consent works
- [ ] Debt transfer without consent fails
- [ ] Circular dependency detection
- [ ] Edge cases (zero amounts, self-debt)

### Integration Tests
- [ ] 3-party triangular trade
- [ ] 5-party circular netting
- [ ] 10-party stress test
- [ ] Consent flow end-to-end
- [ ] Settlement execution

### Field Tests
- [ ] Real community trading circle
- [ ] Monthly netting cycle
- [ ] Dispute resolution
- [ ] User experience feedback

---

## Resources

### Documentation
- [Presentation](../../docs/presentation/presentation.md) - Triangular trade slide
- [Basis Protocol Whitepaper](../../docs/conf/conf.pdf)
- [Mesh Network Demo](../mesh/README.md) - For offline operation

### Scripts
- `calculate_netting.py` - Net position calculation
- `transfer_debt.sh` - Debt transfer execution
- `settle_netting.py` - Optimized settlement
- `visualize_circle.py` - Visualization tool

### External
- [Triangular Trade (Wikipedia)](https://en.wikipedia.org/wiki/Triangular_trade)
- [Debt Netting (Investopedia)](https://www.investopedia.com/terms/n/netting.asp)
- [Multilateral Netting](https://www.investopedia.com/terms/m/multilateral-netting.asp)

---

## License

This demo is part of the ChainCash project, released under a permissive open-source license. See [LICENSE](../../LICENSE) for details.

---

**Built by and for the Commons** 🌱

Free, open source community project. No token, no VC, no corporate control.

**Efficient Community Trading** 🔄

Enabling circular debt cancellation for efficient multi-party settlements.
