# Basis v2 source-to-ErgoTree receipt

This receipt binds the local Basis v2 candidate to exact compiler inputs and
full ErgoTree bytes. It is a build-parity record, not a security, deployment or
activation claim.

## Source identity

- Repository base commit:
  `78475e30362571acf56e4e38276a9d6c0a84ce0c`
- Candidate branch: `a-shannon/basis-v2-contract`
- Source paths:
  - `contracts/offchain/basis-v2.es`
  - `contracts/offchain/basis-token-v2.es`
- The compiler reads UTF-8 with `getLines.mkString("\n")`. It therefore uses LF
  separators and removes the final line terminator independently of checkout
  line endings.

| Compiler input | SHA-256 of normalized UTF-8 bytes |
| --- | --- |
| `basis-v2.es` | `a73f8530b355c26136d2732d6a26766cb8c1cabaa801bc0d2aa30dc548a4d884` |
| `basis-token-v2.es` | `c82d4c5b5fb7648af81b0104110b0289e0e5cf4d3cc9b6ec3e0b49fc30c8cf60` |

The reviewed Windows checkout used `core.autocrlf=true`. Its raw working-file
SHA-256 values were respectively
`db72e1212f89d90555bc7d1e9914fd598ffca0bd2206d2b1b0d604dbf2a68d5d`
and `7e9c64988c3e9bfbb5577b493d4e6031d858f9014e456b6f7152df2e54d02fa2`.
Those raw hashes are informative; the normalized hashes above identify the
actual compiler input.

## Compiler closure

| Input | Version / SHA-256 |
| --- | --- |
| Scala | 2.12.17 |
| SBT | 1.5.2; launcher `b4c0c55d68f11b1510d884641cb1b1456191dac40ddc958bf86c825adc344e16` |
| JDK | Microsoft OpenJDK 17.0.19+10-LTS, 64-bit |
| Ergo Appkit | `org.ergoplatform:ergo-appkit_2.12:6.0.0`; jar `258e2c8e1d3f8d68c82cdb36126d7b1bd5100a70aadf9e4c46dc67fadaf9822b` |
| Sigma State | `org.scorexfoundation:sigma-state_2.12:6.0.2`; jar `0dbd3b31ef94affec83f8f0f6c5a9891c45da1e975ff6016a0574fc5aa1418e6` |
| `build.sbt` | `806c9e6a228f08ef9f1a3c6fb9b11c0aac097157b03925bfbfeaf1e203420ca0` |
| `project/build.properties` | `cb740d250483ffcfe325411426588065e377c7e4ce7a5faf6cfdde97f390ada1` |
| `project/plugins.sbt` | `e1188d30689ed8c50d7e154158aabdac75b5d91612a3f61b89b65fc3a6b88296` |

Compilation uses `AppkitHelpers.compile` through `Constants.compile`, mainnet
network prefix, and block version 4 (the v6 language lane required by
`AvlTree.insertOrUpdate`). No source substitution parameters are used.

Commands:

```text
sbt -batch "runMain chaincash.contracts.BasisV2ReceiptPrinter"
sbt -batch "testOnly chaincash.BasisV2Spec"
```

## Full output bindings

The full serialized bytes, not only their digests, are committed in these
single-line lowercase-hex files:

| Contract | Full-byte file | Byte length | ErgoTree SHA-256 |
| --- | --- | ---: | --- |
| ERG reserve | `contracts/offchain/basis-v2.p2s` | 1,631 | `49d6f487b69277191ff064e5e036a5a07b343b9b57d76931620157f0b4bfef80` |
| token reserve | `contracts/offchain/basis-token-v2.p2s` | 1,912 | `1f8ba4f6a3ef36799372e7555394274aff5d7fe977a2b5b5b2e7ccd4a7bea5f7` |

`BasisV2Spec` recompiles both normalized sources, compares the complete byte
arrays to `Constants`, then decodes and compares each committed golden file.
`BasisV2ReceiptPrinter` prints the source hashes, full-byte hashes and complete
hex without updating any expected file.

## Consumer linkage

`Constants.basisV2ErgoTree` and `Constants.basisTokenV2ErgoTree` are the named
builder/compiler consumers. V1 constants remain separate. Runtime builders,
tracker state, deployment manifests and observed boxes must fail closed unless
their exact bytes match one of these v2 outputs; those cross-repository and
live-state checks are outside this receipt.

## Evidence ceiling

One local matching build plus mockchain fixtures establishes parity for the
recorded closure. It does not establish an independent clean-room rebuild,
compiler trust, target-node acceptance, released-wallet compatibility, current
deployment, live-box lineage, economic safety or production readiness.
