# ChainCash Contracts

This directory contains utilities for deploying and managing ChainCash contracts on the Ergo blockchain.

## Contracts

### Basis Reserve Contract

The Basis reserve contract is an on-chain reserve that backs off-chain payments, allowing for:
- Off-chain payments with no need to create anything on-chain first
- Credit creation capabilities
- Redemption with 2% fee
- Emergency redemption after 7 days

#### Deployment

Use `BasisDeployer` utility to deploy Basis reserve contracts:

```scala
import chaincash.contracts.BasisDeployer
import sigmastate.Values.GroupElementConstant
import sigmastate.eval.CGroupElement
import sigmastate.basics.CryptoConstants

// Example deployment
val ownerKey = GroupElementConstant(CGroupElement(CryptoConstants.dlogGroup.generator))
val trackerNftId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
val reserveTokenId = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"

val deploymentRequest = BasisDeployer.createBasisDeploymentRequest(
  ownerKey,
  trackerNftId,
  reserveTokenId,
  initialCollateral = 1000000000L // 1 ERG
)

println(deploymentRequest)
```

#### Contract Features

- **Redemption Action (0)**: Redeem off-chain notes with tracker signature
- **Top-up Action (1)**: Add more collateral to the reserve
- **Emergency Redemption**: Redeem without tracker signature after 7 days
- **Double Spending Prevention**: AVL tree tracks redeemed timestamps

#### Required Parameters

1. **Owner Public Key**: GroupElement representing reserve owner
2. **Tracker NFT ID**: NFT identifying the tracker service
3. **Reserve Token ID**: Singleton NFT identifying the reserve
4. **Initial Collateral**: Minimum 1 ERG (1000000000 nanoERG)

### Core ChainCash Contracts

- **Reserve Contract**: On-chain collateral management
- **Note Contract**: Digital currency issuance and transfer
- **Receipt Contract**: Redemption receipt management

## Testing

Run tests to verify contract compilation and deployment:

```bash
sbt test
```

## Deployment Process

1. **Compile Contracts**: Use `Constants` object to compile contracts
2. **Generate Addresses**: Get pay-to-script addresses for each contract
3. **Create Deployment Requests**: Use deployment utilities
4. **Submit Transactions**: Send deployment transactions to Ergo blockchain
5. **Monitor**: Use scan requests to monitor contract states

## Network Configuration

- **Mainnet**: NetworkType.MAINNET
- **Testnet**: NetworkType.TESTNET (modify Constants.scala)

## Security Notes

- Always test on testnet before mainnet deployment
- Verify contract addresses before deployment
- Use proper key management for reserve owners
- Monitor tracker services for availability