# ChainCash off-chain contract candidates

## Active source family

`basis-v2.es` and `basis-token-v2.es` are the only contract sources exposed by
production compilation and address tooling. Their committed `.p2s` files are
the exact full-ErgoTree goldens for this candidate generation.

See `basis-v2.md` for the register and transition ABI, and
`basis-v2-reproducibility.md` for the source, compiler and full-byte receipt.
The repository provides no v2 deployment, signing, submission or migration
builder. A compiled address is not evidence of deployment or production
readiness.

## Historical sources

The exact Basis v1 contract sources are retained only under
`src/test/resources/contracts/historical/` for regression tests. Its former
demos and operational walkthroughs are removed from the working tree and remain
recoverable at commit `78475e30362571acf56e4e38276a9d6c0a84ce0c`.
Production `Constants`, printers, classes and the main JAR cannot load or expose
that generation. Moving the sources does not change the authorization of any
immutable v1 box; recovery would require a separate inventory and an exit
already authorized by its exact old ErgoTree.
