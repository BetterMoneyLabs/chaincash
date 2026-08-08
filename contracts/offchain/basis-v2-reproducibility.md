# Basis v2 source-to-ErgoTree receipt

This receipt binds the local Basis v2 candidate to exact compiler inputs and
full ErgoTree bytes. It is a build-parity record, not a security, deployment or
activation claim.

## Source identity

- Repository base commit:
  `78475e30362571acf56e4e38276a9d6c0a84ce0c`
- Immutable source and golden commit:
  `9a274396d5f78f7be5ed76bacee5329c42570317`

| Path | Git blob id |
| --- | --- |
| `contracts/offchain/basis-v2.es` | `47b3e0647f90db2dd8e667641294d66e287d8f6a` |
| `contracts/offchain/basis-token-v2.es` | `48a00b612fe6f4345b322d22408c48100f2cd442` |
| `contracts/offchain/basis-v2.p2s` | `f806b34edab4b18b89a395625db753d7acdf94db` |
| `contracts/offchain/basis-token-v2.p2s` | `debc24531f0adc26292f897fff6b53ed06ff4374` |
| `src/test/scala/chaincash/BasisV2Spec.scala` | `c71a1817cb2f9c0e9b4070670bd0712f7d558c5c` |

- The compiler reads UTF-8 with `getLines.mkString("\n")`. It therefore uses LF
  separators and removes the final line terminator independently of checkout
  line endings.

| Compiler input | SHA-256 of normalized UTF-8 bytes |
| --- | --- |
| `basis-v2.es` | `31ff4271b1c79302064df83a6bfa3d4f6f5f153747002c1c0206b2f3d88b507a` |
| `basis-token-v2.es` | `c7e5a0cf6a12aaedefba79ecd012ac7839a48f683931d1d4e10371d287c58573` |

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
| ERG reserve | `contracts/offchain/basis-v2.p2s` | 1,682 | `2690634924efb22359a776f89f5274d77e067bd8ad0619a6e358a2f96697a0c2` |
| token reserve | `contracts/offchain/basis-token-v2.p2s` | 1,963 | `ba1df64e7d95ecffc4f3d49fcada8baebe59a676eb617737cd010bdb52381cb3` |

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
