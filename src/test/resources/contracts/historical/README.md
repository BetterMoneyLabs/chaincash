# Historical ChainCash contract fixtures

These ErgoScript files are retained only to replay the original ChainCash
prototype tests. They are not production resources, supported deployment
targets, or address-generation inputs.

The files were moved without source changes from commit
`78475e30362571acf56e4e38276a9d6c0a84ce0c`. Their canonical UTF-8 LF
SHA-256 digests are shown below. CRLF is normalized to LF before hashing, while
the final newline is retained.

| Fixture | SHA-256 |
| --- | --- |
| `onchain/reserve.es` | `3f39c13879748230cd08ee10d9970370506509e966229b735af77486843fa335` |
| `onchain/receipt.es` | `69c24be8426e7c7c8fdf77ab248e4549b8cc9832122aed010a54e304357869ea` |
| `onchain/note.es` | `5a145dfa334efa60a26fe25ed136611992f207807ecb628c4c8ec196d940df16` |
| `layer2-old/reserve.es` | `026c29b169f39f75a262590388364aea926a270c6fa78511199c1ae1512336e4` |
| `layer2-old/redemption.es` | `b87614ec6aacbed34a0bf7950629d505cc6f204d1b4e7db52c5b43a133e122c6` |
| `layer2-old/redproducer.es` | `9e9842716a49d631bc67ecf201da42c020d441e3329b9fd7778ea14c9dcc8351` |
| `layer2-old/note.es` | `797b69c823ff392d5a941cbd02b9297341c5926c2ce721ab6735f71f3c181e1b` |

`HistoricalContractFixtures` verifies these canonical digests before exposing
the source to historical tests. Changing a fixture requires an explicit new
lineage and review; it must not silently become a production contract
generation.
