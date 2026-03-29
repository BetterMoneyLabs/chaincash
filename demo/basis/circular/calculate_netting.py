#!/usr/bin/env python3
"""
Calculate net positions for circular trading.

Usage:
    python3 calculate_netting.py --ledger ledger.json
    python3 calculate_netting.py --transactions "alice,bob,10;bob,carol,5"
"""

import argparse
import json
from collections import defaultdict
from typing import Dict, List, Tuple


def calculate_net_positions(transactions: List[Tuple[str, str, int]]) -> Dict[str, int]:
    """
    Calculate net positions for circular trading.
    
    Args:
        transactions: List of (debtor, creditor, amount) tuples
    
    Returns:
        Dict of {participant: net_position}
        Positive = owed money, Negative = owes money
    """
    positions = defaultdict(int)
    
    for debtor, creditor, amount in transactions:
        # Debtor owes (negative)
        positions[debtor] -= amount
        # Creditor is owed (positive)
        positions[creditor] += amount
    
    return dict(positions)


def optimize_settlement(positions: Dict[str, int]) -> List[Tuple[str, str, int]]:
    """
    Optimize settlement to minimize transactions.
    
    Uses greedy algorithm to match debtors with creditors.
    
    Returns:
        List of (payer, payee, amount) for settlement
    """
    # Separate debtors and creditors
    debtors = [(p, -amt) for p, amt in positions.items() if amt < 0]
    creditors = [(p, amt) for p, amt in positions.items() if amt > 0]
    
    # Sort by amount (largest first)
    debtors.sort(key=lambda x: x[1], reverse=True)
    creditors.sort(key=lambda x: x[1], reverse=True)
    
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


def load_ledger(filepath: str) -> List[Tuple[str, str, int]]:
    """Load transactions from ledger JSON file."""
    with open(filepath, 'r') as f:
        ledger = json.load(f)
    
    transactions = []
    for entry in ledger.get('transactions', []):
        debtor = entry.get('debtor') or entry.get('payer')
        creditor = entry.get('creditor') or entry.get('payee')
        amount = entry.get('amount')
        
        if debtor and creditor and amount:
            transactions.append((debtor, creditor, int(amount)))
    
    return transactions


def parse_transactions(trans_str: str) -> List[Tuple[str, str, int]]:
    """Parse transactions from command-line string.
    
    Format: "alice,bob,10;bob,carol,5"
    """
    transactions = []
    for tx in trans_str.split(';'):
        parts = tx.strip().split(',')
        if len(parts) == 3:
            debtor, creditor, amount = parts
            transactions.append((debtor.strip(), creditor.strip(), int(amount)))
    return transactions


def print_positions(positions: Dict[str, int]):
    """Print net positions in formatted table."""
    print("=" * 60)
    print("  Circular Trading - Net Positions")
    print("=" * 60)
    
    total_debt = 0
    total_credit = 0
    
    for participant, position in sorted(positions.items()):
        if position < 0:
            print(f"  {participant:10s}: {-position / 1e9:10.9f} ERG (owes)")
            total_debt += -position
        elif position > 0:
            print(f"  {participant:10s}: {position / 1e9:10.9f} ERG (owed)")
            total_credit += position
    
    print("-" * 60)
    print(f"  Total debt:   {total_debt / 1e9:.9f} ERG")
    print(f"  Total credit: {total_credit / 1e9:.9f} ERG")
    print("=" * 60)


def print_settlements(settlements: List[Tuple[str, str, int]]):
    """Print optimized settlement plan."""
    print()
    print("=" * 60)
    print("  Optimized Settlement Plan")
    print("=" * 60)
    
    total = 0
    for payer, payee, amount in settlements:
        print(f"  {payer:10s} → {payee:10s}: {amount / 1e9:10.9f} ERG")
        total += amount
    
    print("-" * 60)
    print(f"  Total transactions: {len(settlements)}")
    print(f"  Total value:        {total / 1e9:.9f} ERG")
    
    # Estimate fee savings
    original_txs = len(settlements) * 2  # Rough estimate
    fee_per_tx = 1000000  # 0.001 ERG
    savings = (original_txs - len(settlements)) * fee_per_tx
    
    print(f"  Estimated savings:  ~{savings / 1e9:.3f} ERG in fees")
    print("=" * 60)


def main():
    parser = argparse.ArgumentParser(
        description='Calculate net positions for circular trading',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  %(prog)s --ledger ledger.json
  %(prog)s --transactions "alice,bob,10;bob,carol,5;carol,alice,3"
  %(prog)s --transactions "alice,bob,100000000;bob,carol,50000000"
        """
    )
    
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--ledger', help='Path to ledger JSON file')
    group.add_argument('--transactions', help='Transactions string: "alice,bob,10;bob,carol,5"')
    
    parser.add_argument('--output', help='Save results to JSON file')
    parser.add_argument('--verbose', '-v', action='store_true', help='Verbose output')
    
    args = parser.parse_args()
    
    # Load transactions
    if args.ledger:
        print(f"Loading ledger from: {args.ledger}")
        transactions = load_ledger(args.ledger)
    else:
        print(f"Parsing transactions: {args.transactions}")
        transactions = parse_transactions(args.transactions)
    
    if not transactions:
        print("Error: No transactions found")
        return 1
    
    print(f"Loaded {len(transactions)} transactions")
    print()
    
    # Calculate net positions
    positions = calculate_net_positions(transactions)
    print_positions(positions)
    
    # Optimize settlement
    settlements = optimize_settlement(positions)
    print_settlements(settlements)
    
    # Save results if requested
    if args.output:
        result = {
            'positions': positions,
            'settlements': [
                {'payer': p, 'payee': y, 'amount': a}
                for p, y, a in settlements
            ]
        }
        with open(args.output, 'w') as f:
            json.dump(result, f, indent=2)
        print(f"\nResults saved to: {args.output}")
    
    return 0


if __name__ == '__main__':
    exit(main())
