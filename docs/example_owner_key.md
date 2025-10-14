# Example Owner Key

## Overview
This document describes the example owner key created for ChainCash reserve deployment.

## Public Key
- **Hex**: `025ffe0e1a282fc0249320946af4209eb2dd7f250c16946fdd615533092e054bca`
- **Type**: Compressed SEC1 format
- **Usage**: Reserve owner signing key

## Implementation

The key has been integrated into the ChainCash codebase in the following ways:

### 1. KeyUtils Utility Class
Located at: `src/main/scala/chaincash/contracts/KeyUtils.scala`

```scala
// Create GroupElementConstant from hex public key
val ownerKey = KeyUtils.createOwnerKeyFromHex("025ffe0e1a282fc0249320946af4209eb2dd7f250c16946fdd615533092e054bca")

// Use the predefined example key
val exampleKey = KeyUtils.exampleOwnerKey
```

### 2. BasisDeployer Integration
The example key is now used in:
- `BasisDeployer.main()` - for example deployment requests
- `BasisDeployerSpec` - for testing

### 3. Usage in Reserve Contracts
This public key will be stored in the R4 register of reserve contracts to:
- Verify owner signatures for reserve operations
- Authorize note issuance and redemption
- Control reserve management

## Testing
Run the test to verify key conversion:
```bash
sbt "runMain chaincash.contracts.KeyUtils"
```

## Security Notes
- This is an example key for testing purposes
- For production use, generate a new key pair
- Keep private keys secure and never commit them to the repository