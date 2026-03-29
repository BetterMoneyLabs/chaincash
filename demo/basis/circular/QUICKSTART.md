# Circular Trading Demo - Quick Start

## 5-Minute Demo

This guide walks you through a complete circular trading demonstration in 5 minutes.

---

## Prerequisites

```bash
# Install Python 3 (if not already installed)
python3 --version

# No additional dependencies needed for basic demo!
```

---

## Step 1: View Sample Ledger (30 seconds)

```bash
cd /path/to/chaincash/demo/circular
cat sample_ledger.json
```

This shows a 3-party trading circle:
- **Alice owes Bob**: 10 ERG
- **Bob owes Carol**: 5 ERG
- **Carol owes Alice**: 3 ERG

---

## Step 2: Calculate Net Positions (30 seconds)

```bash
python3 calculate_netting.py --ledger sample_ledger.json
```

**Expected Output:**
```
============================================================
  Circular Trading - Net Positions
============================================================
  alice     : 0.070000000 ERG (owes)
  bob       : 0.050000000 ERG (owed)
  carol     : 0.020000000 ERG (owed)
============================================================
```

**What this means:**
- Instead of 3 separate payments (18 ERG total)
- Only 2 payments needed (7 ERG total)
- **61% reduction in transactions!**

---

## Step 3: Execute Debt Transfer (1 minute)

Transfer Alice's debt from Bob to Carol:

```bash
./transfer_debt.sh --debtor alice --from bob --to carol --amount 50000000 --reason "Bob buys from Carol"
```

**Expected Output:**
```
============================================================
  Basis Debt Transfer
============================================================
  Transfer ID:    transfer_1711732200000
  Debtor:         alice (owes the debt)
  From:           bob (original creditor)
  To:             carol (new creditor)
  Amount:         0.050000000 ERG (50000000 nanoERG)
  Reason:         Bob buys from Carol
============================================================
  ✓ Debt Transfer Completed Successfully!
============================================================
```

---

## Step 4: Recalculate Netting (30 seconds)

```bash
python3 calculate_netting.py --ledger ledger.json
```

**New positions after debt transfer:**
```
  alice     : 0.080000000 ERG (owes)   [increased!]
  bob       : 0.000000000 ERG (balanced) [paid off!]
  carol     : 0.080000000 ERG (owed)   [increased!]
```

**Bob is now out of the circle!** His debt to Carol was paid by transferring Alice's debt.

---

## Step 5: Execute Settlement (1 minute)

```bash
python3 settle_netting.py --ledger ledger.json --auto
```

**Expected Output:**
```
============================================================
  Settlement Execution
============================================================
  Optimized Settlement Plan:
  alice      → carol     : 0.080000000 ERG [AUTO]
============================================================
  Settlement Summary
  Planned:     1 transactions
  Executed:    1 transactions
  Fee savings: ~0.002 ERG (vs 3 individual txs)
============================================================
```

---

## Step 6: Visualize (optional, 1 minute)

```bash
# ASCII visualization (no dependencies)
python3 visualize_circle.py --ledger ledger.json --format ascii

# Or with graphviz (if installed)
pip install graphviz
python3 visualize_circle.py --ledger ledger.json --output circle.png
```

---

## What You Demonstrated

✅ **Triangular Trade** - Debt transfer without on-chain transaction  
✅ **Netting** - Reduced 3 transactions to 1  
✅ **Debt Transfer** - Bob paid Carol by transferring Alice's debt  
✅ **Fee Savings** - ~67% reduction in transaction fees  

---

## Try Your Own Scenario

### Create Custom Transactions

```bash
python3 calculate_netting.py --transactions "alice,bob,100;bob,carol,50;carol,dave,30;dave,alice,20"
```

### 5-Party Circle

```bash
python3 calculate_netting.py --transactions "a,b,100;b,c,80;c,d,60;d,e,40;e,a,20"
```

---

## Next Steps

1. **Read Full Documentation**: See [README.md](./README.md)
2. **Try Real Scenarios**: Create your own ledger files
3. **Integrate with Mesh**: Combine with mesh network demo
4. **Production Use**: Implement full tracker server

---

## Troubleshooting

### "command not found: bc"

```bash
# Install bc calculator
sudo apt-get install bc  # Debian/Ubuntu
brew install bc          # macOS
```

### "No module named 'graphviz'"

```bash
# Optional - for visualization only
pip install graphviz
```

---

**Demo Complete!** 🎉

You've successfully demonstrated circular trading and debt netting.
