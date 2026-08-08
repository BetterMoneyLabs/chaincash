# Historical ChainCash contract fixtures

These ErgoScript files are retained only to replay the original ChainCash
prototype tests. They are not production resources, supported deployment
targets, or address-generation inputs.

The files were moved without content changes from commit
`78475e30362571acf56e4e38276a9d6c0a84ce0c`. Their raw SHA-256 digests are:

| Fixture | SHA-256 |
| --- | --- |
| `onchain/reserve.es` | `14ac7744339c70f6d1252948aa85ce9684be1b99b32faae9993d6387e19f724e` |
| `onchain/receipt.es` | `c35f0aa6bbd9e272f11cabbab055c96fd47bfee2fb4d0b159f5ec18f0d131509` |
| `onchain/note.es` | `eeb985b7cd06b99b62b08fdb540c227656984c2de9ff29a4ef31804805b26c56` |
| `layer2-old/reserve.es` | `19b513ce118aa536fd95e13c7b41231caeda74c5a3c51d137ccbdf4093d4de8a` |
| `layer2-old/redemption.es` | `e6aa669a19b32e365ea7f980bfb97798c21e496eb889aeebb8dd88f27a30a930` |
| `layer2-old/redproducer.es` | `9c86583f3a3987ecdbe39f7ac2780c13d3c69c79414b3d5f6ff5a45fe3468a61` |
| `layer2-old/note.es` | `4a7a2e7cb806f0311275c6e41cb8c27b53f180d8511900f6ec09abf68a51319f` |

`HistoricalContractFixtures` verifies these digests before exposing the source
to historical tests. Changing a fixture requires an explicit new lineage and
review; it must not silently become a production contract generation.
