#!/usr/bin/env python3
"""
Execute optimized settlement for circular trading.

Usage:
    python3 settle_netting.py --positions netting_result.json
    python3 settle_netting.py --ledger ledger.json --auto
"""

import argparse
import json
import sys
from typing import Dict, List, Tuple

# Import from calculate_netting
sys.path.insert(0, '.')
from calculate_netting import calculate_net_positions, optimize_settlement, load_ledger


def execute_settlement(payer: str, payee: str, amount: int, auto: bool = False) -> bool:
    """
    Execute a single settlement payment.
    
    In a real implementation, this would:
    1. Create IOU from payer to payee
    2. Get signatures
    3. Update ledger
    
    For demo, we just simulate.
    """
    erg_amount = amount / 1e9
    
    if auto:
        # Auto-execute (simulate)
        print(f"  ✓ {payer:10s} → {payee:10s}: {erg_amount:.9f} ERG [AUTO]")
        return True
    else:
        # Interactive confirmation
        print(f"\n  Settlement: {payer} → {payee}")
        print(f"  Amount: {erg_amount:.9f} ERG")
        response = input("  Execute? (yes/no): ").strip().lower()
        
        if response == 'yes':
            print(f"  ✓ Executed")
            return True
        else:
            print(f"  ✗ Skipped")
            return False


def save_settlement_record(settlements: List[Tuple[str, str, int]], output_file: str):
    """Save settlement plan to JSON file."""
    record = {
        "type": "settlement_plan",
        "settlements": [
            {
                "payer": payer,
                "payee": payee,
                "amount": amount,
                "amount_erg": amount / 1e9
            }
            for payer, payee, amount in settlements
        ],
        "total_transactions": len(settlements),
        "total_value": sum(a for _, _, a in settlements)
    }
    
    with open(output_file, 'w') as f:
        json.dump(record, f, indent=2)
    
    return output_file


def main():
    parser = argparse.ArgumentParser(
        description='Execute optimized settlement for circular trading',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  %(prog)s --positions netting_result.json
  %(prog)s --ledger ledger.json --auto
  %(prog)s --transactions "alice,bob,10;bob,carol,5" --auto
        """
    )
    
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--positions', help='JSON file with net positions')
    group.add_argument('--ledger', help='Ledger JSON file')
    group.add_argument('--transactions', help='Transactions string: "alice,bob,10"')
    
    parser.add_argument('--auto', action='store_true', help='Auto-execute without confirmation')
    parser.add_argument('--output', help='Save settlement record to file')
    
    args = parser.parse_args()
    
    # Load positions
    if args.positions:
        with open(args.positions, 'r') as f:
            data = json.load(f)
            positions = data.get('positions', data)
    elif args.ledger:
        transactions = load_ledger(args.ledger)
        positions = calculate_net_positions(transactions)
    elif args.transactions:
        transactions = []
        for tx in args.transactions.split(';'):
            parts = tx.strip().split(',')
            if len(parts) == 3:
                transactions.append((parts[0].strip(), parts[1].strip(), int(parts[2])))
        positions = calculate_net_positions(transactions)
    else:
        print("Error: Must specify --positions, --ledger, or --transactions")
        return 1
    
    # Print positions
    print("=" * 60)
    print("  Settlement Execution")
    print("=" * 60)
    print()
    print("  Net Positions:")
    
    for participant, position in sorted(positions.items()):
        if position != 0:
            direction = "owes" if position < 0 else "owed"
            amount = abs(position) / 1e9
            print(f"    {participant:10s}: {amount:10.9f} ERG ({direction})")
    
    print()
    
    # Calculate optimized settlements
    settlements = optimize_settlement(positions)
    
    if not settlements:
        print("  No settlements needed (all positions balanced)")
        return 0
    
    # Print settlement plan
    print("  Optimized Settlement Plan:")
    print("  " + "-" * 56)
    
    executed = 0
    for payer, payee, amount in settlements:
        if execute_settlement(payer, payee, amount, args.auto):
            executed += 1
    
    print()
    print("=" * 60)
    print(f"  Settlement Summary")
    print("=" * 60)
    print(f"  Planned:     {len(settlements)} transactions")
    print(f"  Executed:    {executed} transactions")
    print(f"  Skipped:     {len(settlements) - executed} transactions")
    
    total_value = sum(a for _, _, a in settlements)
    print(f"  Total value: {total_value / 1e9:.9f} ERG")
    
    # Estimate savings
    original_txs = len(positions)  # Rough estimate
    fee_per_tx = 1000000  # 0.001 ERG
    savings = (original_txs - executed) * fee_per_tx
    
    print(f"  Fee savings: ~{savings / 1e9:.3f} ERG (vs {original_txs} individual txs)")
    print("=" * 60)
    
    # Save record if requested
    if args.output:
        output_file = save_settlement_record(settlements, args.output)
        print(f"\n  Settlement record saved to: {output_file}")
    
    print()
    print("  Next steps:")
    print("  1. Verify ledger: cat ledger.json")
    print("  2. Calculate new netting: python3 calculate_netting.py --ledger ledger.json")
    print("  3. Execute on-chain settlement if needed")
    print()
    
    return 0


if __name__ == '__main__':
    exit(main())
