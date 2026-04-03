# ChainCash Contracts

This directory contains utilities for deploying and managing ChainCash contracts on the Ergo blockchain.

## Contracts

### Basis Reserve Contract

The Basis reserve contract is an on-chain reserve that backs off-chain payments, allowing for:
- Off-chain payments with no need to create anything on-chain first
- Credit creation capabilities
- Redemption with tracker signature
- Emergency redemption after 3 days

**Compiled Contract Address (P2S):**
```
4ZhBzJfNoUL9Bp993NzJcdUr6CNfuwvwNMgHC2JPHs8ane1jjE3K7gzUQVBNQfJccoLbB2P8xMsa9qZNFgRwgrWs6WGEa38gwF1BDkGwMLh6RJUez5Ge6toZzu7tZo5qYtqUinmckb5q9hcVo6Cpn3w2gcuwCd2sKmRohedxxbpP7vnrQmCNQveB22RN5ZVv8VGJaDUEC3ADCSRjzr5ZzJNBmVbAw2k5sTmoXGm7qJ1YT9gzmAPi97ptJJQXqNJoi1W6coMFwg34Dc21K9TMkKQexnXxon21XrbyWL6fzLGbYBRBiVpiRTeMah9Tc33yN93NVTjHWKvBcxSYiJU7eJy6aiwAHhqxYPtZNhwE196qUEYHX5gnN1xB4CpZA2W2HDuEZREpDPV4xy6g2qucW2fyhgDpscHMxrbaGfRq1zkrvML54z2Da9jpkM6nmZx2KB29HTh1do6L3rrLxnvg5cgANzfYuaWPFEoo6j2ZqjPzLDeSSVhPbkMnw6HhQp2qtzayqWVgCKGRzMFuh8BkpmkFCPKjhUwX6Dgv6DpkuHbJRM7k9YSvPCHRQTSeDJa4B5wuyXMsfFMkAnjR4oaLbSBU2QCgKBLFbGvrRKgAJG9eTSc31x6EtqKFoLN2urEWGsEh1F6cxDh2Ma3izwFLyHAgCcUurRXndm5gy3U4GpKdaJiWtwfhcZspwtJ72gWUBEzuPdcqjEyBc95jVtubHeN95QcZLJkJM88c6m1DPXaTBSfDpL8s3sBySa7
```

### Basis-Token Contract (Token-Based Reserve)

A variant of the Basis contract that uses custom tokens instead of ERG for reserve backing.

**Compiled Contract Address (P2S):**
```
FjrqPyLvFUvPnjM8NgASrt3uZjegdVvpsjxrF5VL7nCgZAYLE4FJbFrJGgZYFHwyoAF1epbTynQhyHjuku4TotT15f6NBFWEWq8NeMgWopBxMkYmrA4X2WiFifCgBWm4nrmKiRyowCn4TbDVu6B5XEEDtB35jpkQyrdQ8jFhJDZsyLT59JaMPvLn931BA7dUdYjK8w8LocNxm8EUU5cm2Q8z7d7Z142pRhbnjEH98yPRkSg8We9ejqzQZpkTpk62uTbGtanQueKwyieN5QTdY1R6C6mBsjHN18rThDfrTqohfY33EzNgjiqNpsuw63MBHjmmh3eQnqRQe8yuDvAn1WvAb8gJujwnLThziucBounzgtEP4Bso7eToR2uZqi9RGgYCDDWL1XigS9kMkxpgRcszByz7WtXXV8jrth7PbqAQ8oRhrQCwH7rsWwj3qARz1x1acyBkbXEvJDAqZhsqUupekcv6aQtMro9WwXSvGfJuXuw7HGHwxGcg7yKQrX1J9g1EoL4F48cZarhMXHwEjgJm6GPE7gReR4THfuRtLLkSLiFbBEPFsjjAUQTKqwMPGGfPZyRT8smxJbaNeiziXnNJnttT4wZv4srEfnWAp3rbwWQ59grKpFsYtTksHRdMYzv2gpexqZD62ggymqP5u1iCpTBNvBGAnXLcY42C3rcoKhEsbrFTVjjGmBL1haDhwz3pidJF5rsEgEjqNw7r6frGbefDBWJzfSZdwSkn22ZZBMTeUbd96XEVJ4Qvx61kAwxT116AH9xW3CVSKAh5Hm5Dkw3oRBD6dYFrhjLjxvEynD6NpgwfAFYDQdCt7FPoM24pxRYsc3x9u1fTeF4DKC1sVrvviZ9pdjGtZDCSQpNk
```

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