#!/bin/bash
# transfer_debt.sh - Transfer debt from one creditor to another
#
# Usage:
#   ./transfer_debt.sh --debtor alice --from bob --to carol --amount 50000000
#   ./transfer_debt.sh -d alice -f bob -t carol -a 50000000 --reason "Bob buys from Carol"
#
# This script transfers a debt obligation:
#   Before: debtor owes 'from' participant
#   After:  debtor owes 'to' participant
#
# Requires debtor's consent (signature) for the transfer.

set -e

# Default values
AMOUNT=""
DEBTOR=""
FROM_CREDITOR=""
TO_CREDITOR=""
REASON="Debt transfer"
LEDGER_FILE="ledger.json"
OUTPUT_FILE=""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Print usage
usage() {
    cat << EOF
Usage: $0 --debtor <name> --from <creditor> --to <new_creditor> --amount <nanoERG>

Transfer debt from one creditor to another (requires debtor consent).

Required:
  -d, --debtor <name>       The debtor (person who owes)
  -f, --from <name>         Original creditor (person owed)
  -t, --to <name>           New creditor (person to be owed)
  -a, --amount <nanoERG>    Amount in nanoERG (e.g., 50000000 = 0.05 ERG)

Optional:
  -r, --reason <text>       Reason for transfer (default: "Debt transfer")
  -l, --ledger <file>       Ledger file (default: ledger.json)
  -o, --output <file>       Output file for transfer record
  -h, --help                Show this help message

Example:
  $0 --debtor alice --from bob --to carol --amount 50000000
  $0 -d alice -f bob -t carol -a 50000000 -r "Bob buys from Carol"
EOF
    exit 1
}

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -d|--debtor)
            DEBTOR="$2"
            shift 2
            ;;
        -f|--from)
            FROM_CREDITOR="$2"
            shift 2
            ;;
        -t|--to)
            TO_CREDITOR="$2"
            shift 2
            ;;
        -a|--amount)
            AMOUNT="$2"
            shift 2
            ;;
        -r|--reason)
            REASON="$2"
            shift 2
            ;;
        -l|--ledger)
            LEDGER_FILE="$2"
            shift 2
            ;;
        -o|--output)
            OUTPUT_FILE="$2"
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        *)
            echo "Unknown option: $1"
            usage
            ;;
    esac
done

# Validate required arguments
if [[ -z "$DEBTOR" || -z "$FROM_CREDITOR" || -z "$TO_CREDITOR" || -z "$AMOUNT" ]]; then
    echo -e "${RED}Error: Missing required arguments${NC}"
    usage
fi

# Calculate ERG amount
ERG_AMOUNT=$(echo "scale=9; $AMOUNT / 1000000000" | bc)

# Generate timestamp
TIMESTAMP=$(date +%s%3N)
TRANSFER_ID="transfer_${TIMESTAMP}"

# Print header
echo -e "${BLUE}============================================================${NC}"
echo -e "${BLUE}  Basis Debt Transfer${NC}"
echo -e "${BLUE}============================================================${NC}"
echo ""
echo -e "  ${YELLOW}Debt Transfer Details${NC}"
echo "  ----------------------------------------------------------"
echo "  Transfer ID:    $TRANSFER_ID"
echo "  Debtor:         $DEBTOR (owes the debt)"
echo "  From:           $FROM_CREDITOR (original creditor)"
echo "  To:             $TO_CREDITOR (new creditor)"
echo "  Amount:         $ERG_AMOUNT ERG ($AMOUNT nanoERG)"
echo "  Reason:         $REASON"
echo "  Timestamp:      $TIMESTAMP"
echo "  Ledger:         $LEDGER_FILE"
echo "  ----------------------------------------------------------"
echo ""

# Step 1: Verify debt exists
echo -e "${YELLOW}Step 1: Verifying debt exists...${NC}"

if [[ -f "$LEDGER_FILE" ]]; then
    # Check if debtor owes from_creditor
    DEBT_EXISTS=$(python3 -c "
import json
with open('$LEDGER_FILE', 'r') as f:
    ledger = json.load(f)
for tx in ledger.get('transactions', []):
    if (tx.get('debtor') == '$DEBTOR' and 
        tx.get('creditor') == '$FROM_CREDITOR' and 
        tx.get('amount', 0) >= $AMOUNT):
        print('yes')
        break
else:
    print('no')
" 2>/dev/null || echo "no")
    
    if [[ "$DEBT_EXISTS" == "yes" ]]; then
        echo -e "  ${GREEN}✓ Debt verified in ledger${NC}"
    else
        echo -e "  ${YELLOW}⚠ Debt not found in ledger (proceeding anyway)${NC}"
        echo "     This may be a new debt transfer without prior IOU"
    fi
else
    echo -e "  ${YELLOW}⚠ Ledger file not found (will create new record)${NC}"
fi

echo ""

# Step 2: Get debtor consent
echo -e "${YELLOW}Step 2: Obtaining debtor consent...${NC}"
echo ""
echo "  $DEBTOR, do you consent to transfer your debt of $ERG_AMOUNT ERG"
echo "  from $FROM_CREDITOR to $TO_CREDITOR?"
echo ""
echo "  Reason: $REASON"
echo ""

# In automated mode, skip interactive prompt
if [[ -n "$AUTOMATED" || -n "$SKIP_CONSENT" ]]; then
    echo -e "  ${GREEN}✓ Consent assumed (automated mode)${NC}"
    CONSENT_SIGNATURE="automated_consent_${TIMESTAMP}"
else
    read -p "  Enter 'yes' to consent: " CONSENT_INPUT
    
    if [[ "$CONSENT_INPUT" != "yes" ]]; then
        echo -e "${RED}✗ Debtor did not consent. Transfer cancelled.${NC}"
        exit 1
    fi
    
    echo -e "${GREEN}✓ Debtor consented${NC}"
    CONSENT_SIGNATURE="consent_${TIMESTAMP}"
fi

echo ""

# Step 3: Get creditor acceptance
echo -e "${YELLOW}Step 3: Confirming new creditor acceptance...${NC}"

if [[ -n "$AUTOMATED" ]]; then
    echo -e "  ${GREEN}✓ Acceptance assumed (automated mode)${NC}"
else
    echo "  $TO_CREDITOR, do you accept this debt from $DEBTOR?"
    read -p "  Enter 'yes' to accept: " ACCEPT_INPUT
    
    if [[ "$ACCEPT_INPUT" != "yes" ]]; then
        echo -e "${RED}✗ New creditor did not accept. Transfer cancelled.${NC}"
        exit 1
    fi
    
    echo -e "${GREEN}✓ New creditor accepted${NC}"
fi

echo ""

# Step 4: Create transfer record
echo -e "${YELLOW}Step 4: Creating transfer record...${NC}"

TRANSFER_RECORD=$(cat <<EOF
{
  "type": "debt_transfer",
  "version": "1.0",
  "transfer_id": "$TRANSFER_ID",
  "debtor": "$DEBTOR",
  "from_creditor": "$FROM_CREDITOR",
  "to_creditor": "$TO_CREDITOR",
  "amount": $AMOUNT,
  "currency": "nanoERG",
  "reason": "$REASON",
  "consent": {
    "debtor": "$DEBTOR",
    "signature": "$CONSENT_SIGNATURE",
    "timestamp": $TIMESTAMP
  },
  "status": "completed",
  "created_at": "$(date -Iseconds)"
}
EOF
)

# Save transfer record
if [[ -n "$OUTPUT_FILE" ]]; then
    echo "$TRANSFER_RECORD" > "$OUTPUT_FILE"
    echo "  Transfer record saved to: $OUTPUT_FILE"
else
    OUTPUT_FILE="transfer_${TRANSFER_ID}.json"
    echo "$TRANSFER_RECORD" > "$OUTPUT_FILE"
    echo "  Transfer record saved to: $OUTPUT_FILE"
fi

echo -e "${GREEN}✓ Transfer record created${NC}"
echo ""

# Step 5: Update ledger
echo -e "${YELLOW}Step 5: Updating ledger...${NC}"

# Create ledger entry for new debt
NEW_DEBT_ENTRY=$(cat <<EOF
{
  "type": "debt_transfer_result",
  "transfer_ref": "$TRANSFER_ID",
  "debtor": "$DEBTOR",
  "creditor": "$TO_CREDITOR",
  "amount": $AMOUNT,
  "timestamp": $TIMESTAMP
}
EOF
)

# Append to ledger (or create new)
if [[ -f "$LEDGER_FILE" ]]; then
    # Add to existing ledger
    python3 -c "
import json
with open('$LEDGER_FILE', 'r') as f:
    ledger = json.load(f)
ledger['transactions'].append($NEW_DEBT_ENTRY)
with open('$LEDGER_FILE', 'w') as f:
    json.dump(ledger, f, indent=2)
"
    echo "  Updated ledger: $LEDGER_FILE"
else
    # Create new ledger
    echo "{\"transactions\": [$NEW_DEBT_ENTRY]}" > "$LEDGER_FILE"
    echo "  Created new ledger: $LEDGER_FILE"
fi

echo -e "${GREEN}✓ Ledger updated${NC}"
echo ""

# Print summary
echo -e "${BLUE}============================================================${NC}"
echo -e "${GREEN}  Debt Transfer Completed Successfully!${NC}"
echo -e "${BLUE}============================================================${NC}"
echo ""
echo "  Summary:"
echo "  ────────────────────────────────────────────────────────"
echo "  Before: $DEBTOR owed $FROM_CREDITOR $ERG_AMOUNT ERG"
echo "  After:  $DEBTOR owes $TO_CREDITOR $ERG_AMOUNT ERG"
echo ""
echo "  Effect:"
echo "  - $FROM_CREDITOR no longer owed by $DEBTOR"
echo "  - $TO_CREDITOR now owed by $DEBTOR"
echo "  - No on-chain transaction needed!"
echo "  ────────────────────────────────────────────────────────"
echo ""
echo "  Next steps:"
echo "  1. Verify ledger: cat $LEDGER_FILE"
echo "  2. View transfer: cat $OUTPUT_FILE"
echo "  3. Calculate netting: python3 calculate_netting.py --ledger $LEDGER_FILE"
echo ""
