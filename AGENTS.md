# ChainCash Agents Architecture

This document describes the agent-based architecture of the ChainCash protocol - a peer-to-peer money creation system built on the Ergo blockchain.

## Overview

ChainCash implements a decentralized monetary system where different agents manage the lifecycle of digital notes backed by collateral and trust. The system enables self-sovereign banking where each participant can issue, transfer, and redeem digital currency.

## Core On-Chain Agents

### Reserve Contract Agent
**File**: `contracts/onchain/reserve.es`

**Responsibilities**:
- Holds ERG collateral backing issued notes
- Manages reserve owner's public key
- Handles note redemption with 2% fee
- Supports reserve top-up operations
- Issues new notes against collateral
- Maintains ownership history via AVL tree

**Key Functions**:
- `createReserve`: Initialize new reserve with collateral
- `topUpReserve`: Add more collateral to existing reserve
- `issueNote`: Create new notes backed by reserve collateral
- `redeemNote`: Redeem notes against reserve with fee

### Note Contract Agent
**File**: `contracts/onchain/note.es`

**Responsibilities**:
- Represents ChainCash notes (digital currency)
- Manages note ownership and transfers
- Maintains spending chain history in AVL tree
- Handles redemption operations
- Enforces signature verification for transfers

**Key Functions**:
- `createNote`: Issue new note from reserve
- `transferNote`: Transfer note to new owner
- `redeemNote`: Redeem note against reserve
- `verifySpendingChain`: Validate spending history

### Receipt Contract Agent
**File**: `contracts/onchain/receipt.es`

**Responsibilities**:
- Created during note redemption
- Allows re-redemption against earlier reserves
- Automatically burns after 3 years
- Tracks redemption position and history

**Key Functions**:
- `createReceipt`: Generate receipt during redemption
- `reRedeem`: Re-redeem against earlier reserves
- `autoBurn`: Self-destruct after expiration

## Off-Chain Management Agents

### Wallet Agent
**File**: `src/main/scala/chaincash/offchain/WalletUtils.scala`

**Responsibilities**:
- Manages user addresses and keys
- Fetches unspent boxes from blockchain
- Handles transaction fee calculations
- Tracks user's notes and reserves

**Key Operations**:
- Address management and key generation
- Box scanning and filtering
- Fee estimation and transaction building

### Reserve Management Agent
**File**: `src/main/scala/chaincash/offchain/ReserveUtils.scala`

**Responsibilities**:
- Creates new reserves with collateral
- Associates reserves with public keys
- Handles reserve funding operations
- Manages reserve lifecycle

**Key Operations**:
- Reserve creation and funding
- Collateral management
- Reserve state tracking

### Note Management Agent
**File**: `src/main/scala/chaincash/offchain/NoteUtils.scala`

**Responsibilities**:
- Creates new notes against reserves
- Handles note transfers between users
- Manages signature creation for spending
- Updates ownership history

**Key Operations**:
- Note issuance from reserves
- Transfer processing and validation
- Spending chain maintenance

### Tracking Agent
**File**: `src/main/scala/chaincash/offchain/TrackingUtils.scala`

**Responsibilities**:
- Scans blockchain for new reserves and notes
- Maintains local database of system state
- Processes blocks and updates local state
- Tracks user-owned reserves and notes

**Key Operations**:
- Blockchain scanning and indexing
- State synchronization
- Event processing and persistence

## Server Agent

**File**: `src/main/scala/chaincash/offchain/server/model.scala`

**Responsibilities**:
- Implements acceptance predicates for notes
- Manages trust-based and collateral-based reserves
- Processes exchange transactions
- Implements mutual credit clearing

**Key Features**:
- Client-side validation of note quality
- Customizable acceptance policies
- Trust scoring and reserve evaluation
- Exchange rate management

## Agent Interaction Patterns

### Issuance Flow
```
Reserve Agent → Note Agent
1. User funds reserve with collateral
2. Reserve Agent creates new notes
3. Note Agent manages note lifecycle
```

### Transfer Flow
```
Note Agent → Note Agent
1. Current owner signs note transfer
2. New owner receives note with updated spending chain
3. Reserve Agent verifies transfer validity
```

### Redemption Flow
```
Note Agent → Reserve Agent → Receipt Agent
1. Note holder initiates redemption
2. Reserve Agent processes redemption with fee
3. Receipt Agent creates redemption receipt
4. Receipt allows re-redemption against earlier reserves
```

## System Architecture Principles

### Collective Backing
- Notes are backed by all previous spenders in the chain
- Each spender adds their collateral or trust to the backing
- Redemption can occur against any reserve in the spending history

### Self-Sovereign Banking
- Each participant acts as their own bank
- Customizable acceptance policies per user/server
- No central authority controlling issuance or acceptance

### Trust Integration
- Supports both collateral-based reserves (ERG backing)
- Supports trust-based reserves (reputation/credit)
- Mixed backing combining multiple reserve types

### Client-Side Validation
- Each receiver validates notes based on their own criteria
- No global consensus on note quality
- Decentralized acceptance decisions

## Agent Communication

### On-Chain Communication
- Agents communicate via transaction inputs/outputs
- State changes recorded on blockchain
- Immutable spending chain maintained in AVL trees

### Off-Chain Coordination
- Database state synchronization
- Blockchain event monitoring
- Local state management and caching

## Implementation Notes

### Data Structures
- **AVL Trees**: Used for maintaining spending chains and ownership history
- **Registers**: Store public keys, amounts, and metadata
- **Tokens**: Represent notes and receipts as first-class assets

### Security Considerations
- Signature verification for all transfers
- Collateral ratio enforcement
- Time-based expiration for receipts
- Fee mechanisms to prevent spam

### Scalability
- Client-side validation reduces blockchain load
- Local state management for performance
- Parallel processing of independent transactions

## Testing Agents

Test coverage includes:
- Reserve creation and funding
- Note issuance and transfers
- Redemption flows
- Spending chain validation
- Fee calculations
- Edge cases and error conditions

See test files in `src/test/scala/chaincash/` for detailed agent testing.

---

*This architecture enables a global monetary system with decentralized issuance where each participant can define their own acceptance rules while maintaining collective backing through the spending chain.*