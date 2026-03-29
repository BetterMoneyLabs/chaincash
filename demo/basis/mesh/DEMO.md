# Mesh Network Demo - Community Trading over Basis Protocol

## Overview

This demo showcases the **Basis protocol** enabling peer-to-peer credit-based trading in disconnected communities using mesh networking technology, as described in the [presentation](../../docs/presentation/presentation.md).

## Running the Demo

The full implementation requires:
1. Mobile wallet apps with mesh connectivity (Bluetooth/WiFi Direct)
2. Local tracker server for the community
3. Gateway node for blockchain sync

For now, review the scenarios in the presentation and the architecture in this README.

## Key Scenarios

### 1. Offline IOU Creation
- Alice creates an IOU note to Bob without Internet over mesh
- Tracker signs locally and maintains community ledger via mesh, 
  it tries to update a short cryptographic digest of the ledger to the blockchain
- Note transfer completes without blockchain
- When Internet is found, a note can be redeemed via blockchain.
- In principle, only ont point of Internet connection is enough. State of blockchain can be prooven to anyone 
  in the community using NiPoPoWs supported by Ergo blockchain. 

### 2. IOU Transfer
- Bob transfers Alice's IOU to Carol
- Partial payments supported
- Endorsement chain maintained

### 3. Triangular Trade
- A owes B, B owes C, C owes A
- Net positions calculated
- Minimal on-chain settlement

### 4. AI Agent Economy
- Autonomous agents create credit relationships
- No human intermediaries
- Automated settlement

### 5. Micropayments
- Pay-per-article without subscriptions
- Aggregated settlement
- No on-chain fees per article

## Architecture

See the detailed architecture in the main [README.md](./README.md).

## Implementation Status

- ✅ Conceptual design complete
- ✅ Test scenarios documented
- 🚧 Full implementation in progress
- 🚧 Mobile wallet app
- 🚧 Mesh networking layer

## Resources

- [Presentation](../../docs/presentation/presentation.md)
- [Whitepaper](../../docs/conf/conf.pdf)
- [Main README](./README.md)
