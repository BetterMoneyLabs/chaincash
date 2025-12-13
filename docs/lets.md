How to implement LETS on ChainCash
----------------------------------

This article describes how Local Exchange Trading System (LETS) on top of ChainCash by using ChainCash Server. 

LETS
----

A local exchange trading system (LETS) is a local mutual credit association in which members are allowed 
to create common credit money individually, written into a common ledger.

As an example, assume that Alice, with zero balance, is willing to buy a litre of raw milk from Bob.

First, they agree on a price; for example, assume that the price is about 2 Euro (as Alice and Bob are living 
in Ireland). After the deal is written into a ledger, Alice's balance becomes -2 (minus two) Euro, and Bob's balance 
becomes 2 Euro. Then Bob may spend his 2 Euro, for example, on homemade beer from Charlie. Such systems often impose 
limits on negative balances, and sometimes even on positive ones, to promote exchange in the community.

LETS on ChainCash
-----------------

* LETS members are decided off-chain
* LETS members white-list each other in ChainCash Server
* standard CCIP-1 contracts are used
* there is no requirement for min reserve value, so it can be close to zero ERG (slightly above to cover 
  storage rent requirements, eg 0.001 ERG)
* then for Alice to pay to Bob in our example, she is issuing a note with needed amount and pays Bob with it. Then 
  Bob may use it to pay Charlie. Bob may use multiple notes in one transaction
* Alice's balance at any time can be calculated as total value of notes she holds at the moment minus total value of 
  all the note she ever issued. It may be negative.

Mutual Credit Clearing 
------------------------

If Alice holds a note issued by Charlie, and Charlie holds a note issued by Alice, and both notes are of the same value, 
they can do clearing. For that, they create a single transaction which is redeeming both notes but without decreasing 
reserves, and sign it (signatures from both are needed, but for different inputs). There's "mutual credit clearing" 
test in `ChainCashSpec.scala` which can be used as an example of how to construct mutual credit clearing transaction.

If notes are of different values, bigger one's can spend it to self to get two notes (payment and change), with one of 
them being equal to counteparty's note, and then clearing is possible.


Extensions
----------

Using ChainCash contract is not the efficient option for implementing simple LETS just (in comparison with LETS-specific 
contracts in https://docs.ergoplatform.com/uses/lets/trustless-lets/#implementation). But with ChainCash a LETS can be 
a part of more complex financial system. And chain of ownership can be useful for integration.

For example, if a note has a signature of special LETS member on it (eg a local municipality), it can be accepted by a 
party outside LETS, and if the party has a reserve, then the note may have value for other non-LETS agents. Also, we 
can imagine a note being accepted if enough known LETS members have signed it.
