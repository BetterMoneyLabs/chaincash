#!/usr/bin/env python3
"""
Visualize circular trading network.

Usage:
    python3 visualize_circle.py --ledger ledger.json --output circle.png
    python3 visualize_circle.py --transactions "alice,bob,10;bob,carol,5"
"""

import argparse
import json
import sys
from typing import Dict, List, Tuple, Optional

try:
    import graphviz
    HAS_GRAPHVIZ = True
except ImportError:
    HAS_GRAPHVIZ = False


def create_digraph(
    transactions: List[Tuple[str, str, int]],
    positions: Optional[Dict[str, int]] = None,
    title: str = "Circular Trading Network"
) -> graphviz.Digraph:
    """
    Create Graphviz digraph for visualization.
    
    Args:
        transactions: List of (debtor, creditor, amount) tuples
        positions: Optional net positions for coloring
        title: Graph title
    
    Returns:
        Graphviz Digraph object
    """
    dot = graphviz.Digraph(comment=title)
    dot.attr(rankdir='LR', size='10,6')
    dot.attr('node', shape='box', style='filled', fillcolor='lightblue')
    dot.attr('edge', color='gray')
    
    # Add title
    dot.attr(label=title, fontsize='20')
    
    # Track edges for aggregation
    edges = {}
    
    # Add transactions as edges
    for debtor, creditor, amount in transactions:
        key = (debtor, creditor)
        if key in edges:
            edges[key] += amount
        else:
            edges[key] = amount
    
    # Add nodes and edges
    participants = set()
    for (debtor, creditor), amount in edges.items():
        participants.add(debtor)
        participants.add(creditor)
        
        erg_amount = amount / 1e9
        label = f"{erg_amount:.2f} ERG"
        
        # Color by net position if provided
        if positions:
            debtor_pos = positions.get(debtor, 0)
            creditor_pos = positions.get(creditor, 0)
            
            if debtor_pos < 0:
                dot.node(debtor, fillcolor='lightcoral')  # Owes money
            elif debtor_pos > 0:
                dot.node(debtor, fillcolor='lightgreen')  # Owed money
            
            if creditor_pos < 0:
                dot.node(creditor, fillcolor='lightcoral')
            elif creditor_pos > 0:
                dot.node(creditor, fillcolor='lightgreen')
        
        dot.edge(debtor, creditor, label=label, penwidth='2')
    
    return dot


def print_ascii_visualization(transactions: List[Tuple[str, str, int]], positions: Dict[str, int]):
    """Print ASCII art visualization for terminals without graphviz."""
    print()
    print("=" * 60)
    print("  Circular Trading Network (ASCII)")
    print("=" * 60)
    print()
    
    # Simple ASCII representation
    participants = set()
    for debtor, creditor, _ in transactions:
        participants.add(debtor)
        participants.add(creditor)
    
    # Print participants with positions
    print("  Participants:")
    print("  " + "-" * 56)
    for p in sorted(participants):
        pos = positions.get(p, 0)
        if pos < 0:
            status = f"owes {-pos / 1e9:.2f} ERG"
            symbol = "📤"
        elif pos > 0:
            status = f"owed {pos / 1e9:.2f} ERG"
            symbol = "📥"
        else:
            status = "balanced"
            symbol = "✓"
        
        print(f"    {symbol} {p:12s}: {status}")
    
    print()
    print("  Transaction Flow:")
    print("  " + "-" * 56)
    
    # Group by debtor
    by_debtor = {}
    for debtor, creditor, amount in transactions:
        if debtor not in by_debtor:
            by_debtor[debtor] = []
        by_debtor[debtor].append((creditor, amount))
    
    for debtor in sorted(by_debtor.keys()):
        for creditor, amount in by_debtor[debtor]:
            erg = amount / 1e9
            print(f"    {debtor:12s} ──{erg:>8.2f} ERG──► {creditor}")
    
    print()
    print("=" * 60)


def main():
    parser = argparse.ArgumentParser(
        description='Visualize circular trading network',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  %(prog)s --ledger ledger.json --output circle.png
  %(prog)s --transactions "alice,bob,10;bob,carol,5"
  %(prog)s --ledger ledger.json --format ascii
        """
    )
    
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--ledger', help='Path to ledger JSON file')
    group.add_argument('--transactions', help='Transactions string: "alice,bob,10"')
    
    parser.add_argument('--output', '-o', help='Output file (PNG, SVG, PDF)')
    parser.add_argument('--format', '-f', choices=['png', 'svg', 'pdf', 'ascii'], 
                        default='png', help='Output format')
    parser.add_argument('--title', '-t', default='Circular Trading Network',
                        help='Graph title')
    
    args = parser.parse_args()
    
    # Load transactions
    if args.ledger:
        with open(args.ledger, 'r') as f:
            ledger = json.load(f)
        transactions = [
            (tx.get('debtor') or tx.get('payer'),
             tx.get('creditor') or tx.get('payee'),
             int(tx.get('amount', 0)))
            for tx in ledger.get('transactions', [])
        ]
    elif args.transactions:
        transactions = []
        for tx in args.transactions.split(';'):
            parts = tx.strip().split(',')
            if len(parts) == 3:
                transactions.append((parts[0].strip(), parts[1].strip(), int(parts[2])))
    else:
        print("Error: Must specify --ledger or --transactions")
        return 1
    
    if not transactions:
        print("Error: No transactions found")
        return 1
    
    # Calculate positions
    from calculate_netting import calculate_net_positions
    positions = calculate_net_positions(transactions)
    
    # Choose output format
    if args.format == 'ascii' or not HAS_GRAPHVIZ:
        if not HAS_GRAPHVIZ and args.format != 'ascii':
            print("Note: graphviz not installed, using ASCII output")
            print("      Install with: pip install graphviz")
            print()
        
        print_ascii_visualization(transactions, positions)
        
        if args.output and args.format == 'ascii':
            # Save ASCII to file
            import io
            from contextlib import redirect_stdout
            
            f = io.StringIO()
            with redirect_stdout(f):
                print_ascii_visualization(transactions, positions)
            
            with open(args.output + '.txt', 'w') as file:
                file.write(f.getvalue())
            
            print(f"ASCII visualization saved to: {args.output}.txt")
    else:
        # Create graphviz visualization
        dot = create_digraph(transactions, positions, args.title)
        
        # Render
        if args.output:
            # Remove extension for graphviz
            output_base = args.output.rsplit('.', 1)[0] if '.' in args.output else args.output
            output_path = dot.render(output_base, format=args.format)
            print(f"Visualization saved to: {output_path}")
        else:
            # Save to default location
            output_path = dot.render('circular_trading', format=args.format)
            print(f"Visualization saved to: {output_path}")
        
        print(f"Format: {args.format.upper()}")
    
    print()
    print("Summary:")
    print(f"  Participants: {len(positions)}")
    print(f"  Transactions: {len(transactions)}")
    
    total_value = sum(abs(a) for _, _, a in transactions)
    print(f"  Total value:  {total_value / 1e9:.2f} ERG")
    
    return 0


if __name__ == '__main__':
    exit(main())
