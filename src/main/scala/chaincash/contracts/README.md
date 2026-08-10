# ChainCash contract tooling

Production tooling exposes only the reviewed Basis v2 candidate sources:

- `contracts/offchain/basis-v2.es`
- `contracts/offchain/basis-token-v2.es`
- `contracts/offchain/basis-v2.p2s`
- `contracts/offchain/basis-token-v2.p2s`

`Constants.publishedContracts` is the sole address-printer allowlist.
`Printer` emits only those two v2 addresses, while `BasisV2ReceiptPrinter`
emits deterministic source and full-ErgoTree material for receipt comparison.

```bash
sbt 'runMain chaincash.contracts.Printer'
sbt 'runMain chaincash.contracts.BasisV2ReceiptPrinter'
```

There is intentionally no production deployment, note-construction,
redemption, signing, submission or broadcast helper in this repository. The
commands above inspect candidate bytes; they do not create a deployable
transaction or establish node, wallet, mempool, deployment or production
readiness.

Basis v1 and the original ChainCash on-chain/Layer-2 prototypes are test-only
historical fixtures. See `docs/legacy-contract-retirement.md` for their exact
source identities and recovery boundary.

Run `sbt verifyMainJarRetirement` to inspect the actual production JAR for
retired classes and resources. Run the Scala suites for source-to-ErgoTree and
transition regression evidence.
