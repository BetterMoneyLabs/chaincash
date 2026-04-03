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
DiBDQZMDeaE3NoLZeYAiHocwgdbZ6a4AYdbpqY5MsoCtx4UdgLz9s8rQYZhLMrKSjGtZumVXCcfY6jwwBrhh4fW5RWFbtfQP1NT9UKmXP8vM2Exq1Z3HdgB1ZSW4SULYoMtLQoF2N2Nktk9JZc7NHdeeKvq8otKog7e2Qc4V6gbH2pobf8r3AmQPw7NyrceUfC2jDbHhNoTiXoi9iihrgNzC6KmmTivyEgMbTFEFc8HznjPcoM8XgXEbtfkWiXcx9gPVuCVR8gMTcnfkVySk5xTXwmkgndS7iiSBUPJtoB66oASw97T83ZUW5noEajBrMMbeYA9aQfD6AgEipo8bjimwuCVRKVQhJU6k7eviDufpMkKmzR26X41V8A7QbA4dFi8W4NSQPjexPasWHUCm4vy22hopvcAYuduLHkmVCdzgFCTYURXTkCB2HYsUttw6XD8ukPTed5fv7faHKkqug6CPo66o3cz8T6go2hoRgKY4UGBgbzXFipgreTo3BPYMVEcMoG4jr5tDhaMwqg99tyLkxj4pr3AjiCGbA4xC7zmmA3BYqzX4uRwRiQVYGKnLbRWs83hYsdAAdGPsd1z7waKgpSv1KFce5YVoPYNRVnVC4YFxauKGTS7Ay8EWb3cXMyfxZDg3Vx2FzN1h5ZCmbUgUKSQGTYp9qPpzatgYNrd8R6siKuQeVgkHmzGw3GoJyH5SeciDKqysFSXeVm8i8562DTeiipaUvpbWqFFbfR96Euw5Prr469A48HnANSk8XdHuRYM6tKwg7L4NcpyuQ
```

### Basis-Token Contract (Token-Based Reserve)

A variant of the Basis contract that uses custom tokens instead of ERG for reserve backing.

**Compiled Contract Address (P2S):**
```
uZt1BBZd4ZQqesF4frpHpo6ffgVFpZP7q3iQKGDz6CA173JrwbTz1gWni1xhYkTKBKXAhCjw83wjRx6mFN1sN5sJPapFGHsYW91UPskG8nmg2E6qmPqGXsZfpSmBM4s5d7uS7hQWZK4bt8iwMwxksZxrLsoGjrxZtaS2DXwYto9qLKbUmaWGLkHgEuN4foV7skXi3WhmaFqghkMq9gT7foprXuwrkDW3KYP2FMg9toBa9KPR1jXEGs7AuGnoJ9hDydXgRrv6tGXHgYmSD2Yrdhg5Qq8bV6PWYF6mDF6vGdeSkdHKhBMFrp1EhWYwai5kxd4CxAzJUuwKkiNt4AppByZCUYaY1cjYs6SAB7LPrHQHEcx5CFrcTyUbJL2RvkjHa84Qqtt2faRHkHpHLD4GhYYXsjPSsnEJ4EHDLTtBinqm1UNm4SLPwbqky4bHzHourt9p1kUkHMTJezuHrJqwANouTsa7JiD2DwiDy84SSF55uze1pmS1CN3ecxyZtUVdcH2oGhGx19buWJziqDWAFcaWMiEnGnLS7AASDpDwJ87ipvuQbnL7sm2gESJa2jDnGnCc8wyLo6VKqgiaTrjThyipQmMgxSaGaSG2tcXK4FmbEQtrTHnUC9xjnjo9zxFiTN6yUFh7QYHVjBkNfY1VnpWo9SVGKiji88kPqeURJwJwKyQeBS3E8tjs2scw4xEmupb6qLXYq3MeswxmFS4Mf9QJ2441CdkUf7aZtJDSHo9FUpATChQHzWMYTY9prdE37qaZnAFo1jUPLdbgbcpWt7bKr9ddtL7QamvXb4odkwWVvT527UJaiCk4EGjgKvCUtz1pv5iSxJawNQhJujC33sgRY3RSVu5gkZWvcSEVTkLYJXJy
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