# ChainCash - elastic peer-to-peer money creation via trust and blockchain assets

This repository contains whitepaper and some prototyping code for 
ChainCash, a protocol to create money in self-sovereign way via trust or collateral, with collective backing and 
individual acceptance. 

## Protocol Lifecycle

The original on-chain reserve/note/receipt prototype and the experimental
Layer-2 prototype are historical, test-only sources. They are not compiled,
addressed, or activated by production code. See
[`docs/legacy-contract-retirement.md`](docs/legacy-contract-retirement.md).

The production-code contract surface contains only the reviewed Basis v2
candidate sources and their frozen full-ErgoTree goldens. Basis v1 is also
retired to test-only fixtures. No deployment, signing, submission, broadcast
or in-place migration path is provided.

## Intro 

We consider money as a set of digital notes, and every note is collectively backed 
by all the previous spenders of the note. Every agent may create reserves to be used 
as collateral. When an agent spends note, whether received previously from another 
agent or just created by the agent itself, it is attaching its signature to it. 
A note could be redeemed at any time against any of reserves of agents previously 
signed the note. We allow an agent
to issue and spend notes without a reserve. It is up to agent's counter-parties
then whether to accept and so back an issued note with collateral or agent's
trust or not.

As an example, consider a small gold mining cooperative in Ghana issuing a
note backed by (tokenized) gold. The note is then accepted by the national government as mean of tax payment. Then the government is using the note (which
is now backed by gold and also trust in Ghana government, so, e.g. convertible
to Ghanaian Cedi as well) to buy oil from a Saudi oil company. Then the oil
company, having its own oil reserve also, is using the note to buy equipment
from China. Now a Chinese company has a note which is backed by gold, oil,
and Cedis.

Economic agent's individual note quality estimation predicate Pi(n) is considering collaterals
and trust of previous spenders. Different agents may have different collateralization 
estimation algorithm (by analyzing history of the single note n, or
all the notes known), different whitelists, blacklists, or trust scores assigned to
previous spenders of the note n etc. So in general case payment sender first
need to consult with the receiver on whether the payment (consisting of one or
multiple notes) can be accepted. However, in the real world likely there will be
standard predicates, thus payment receiver (e.g. an online shop) may publish
its predicate (or just predicate id) online, and then the payment can be done
without prior interaction.

## Whitepaper And Other Materials

High-level description of ChainCash protocol and its implementation can be found
in the [whitepaper](https://github.com/BetterMoneyLabs/chaincash/blob/master/docs/conf/conf.pdf).

More introductory materials:

* [Video presentation from 1st Stability Workshop](https://youtu.be/gTwKxizAXZQ)
* [The World Needs For More Collateral](https://www.ergoforum.org/t/the-world-needs-for-more-collateral/4451) - forum thread
* [Video presentation from Ergo Summit](https://www.youtube.com/watch?v=NxIlIpO6ZVI)
* [Video: ChainCash, part two](https://www.youtube.com/watch?v=fk8ZFvNFDYc)

## Retired Basis v1 demos

The former Basis v1 examples and operational walkthroughs are removed from the
working tree so they cannot be mistaken for supported execution or deployment
paths. They remain recoverable from Git history at commit
`78475e30362571acf56e4e38276a9d6c0a84ce0c` for historical research.

## ChainCash Server

ChainCash server is software acting as a self-sovereign bank with client-side notes
validation. It can be found at [https://github.com/BetterMoneyLabs/chaincash-rs](https://github.com/BetterMoneyLabs/chaincash-rs). 
Initial version of [design document](docs/server.md) is also available. The server has basic HTTP API but no any UI.

## Contents Of This Repository

* Whitepaper - https://github.com/ChainCashLabs/chaincash/blob/master/docs/conf/conf.pdf
High-level description of ChainCash protocol and its implementation

* Contracts - `contracts/offchain/` contains only the Basis v2 candidate family
  and its exact `.p2s` goldens. Basis v1 and the former on-chain and Layer-2
  prototypes are pinned historical fixtures under
  `src/test/resources/contracts/historical/`.

* Modelling - https://github.com/kushti/chaincash/tree/master/src/main/scala/chaincash/model
Contract-less and blockchain-less models of ChainCash entities and one of notes collateralization 
estimation options.
* Tests - `src/test/scala/chaincash/ChainCashSpec.scala` replays the historical
  note-contract transitions through test-only fixtures.
* Offchain part - `src/main/scala/chaincash/offchain/` retains rough prototype
  tracking and persistence code, but no production legacy transaction builder.
  For application development, prefer ChainCash Server, which
 is available at [https://github.com/BetterMoneyLabs/chaincash-rs](https://github.com/BetterMoneyLabs/chaincash-rs) .

## Communications

Join discussion groups for developers and users:

* Telegram: [https://t.me/chaincashtalks](https://t.me/chaincashtalks)

## Candidate-byte inspection

The production commands print only the v2 candidate addresses and the
source-to-ErgoTree receipt material:

```scala
sbt 'runMain chaincash.contracts.Printer'
sbt 'runMain chaincash.contracts.BasisV2ReceiptPrinter'
```

These commands do not generate transactions. The printer emits only entries in
`Constants.publishedContracts`; all v1 and legacy addresses and builders are
deliberately unavailable from production tooling.
