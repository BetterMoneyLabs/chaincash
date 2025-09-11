# Basis - offchain IOU money for digital economies and communities

In this writing, we propose Basis, efficient offchain cash system, backed by on-chain reserves but also allowing for 
creating credit (unbacked IOU money). Its use cases are now thought as follows:

* micropayments, such as payments for content, services, resources usage in p2p and distributed systems. Notable 
difference from Lightning / FediMint / Cashu etc is that here a service can be provided on credit (within certain limits),
which would boost growth for many services, allow for globally working alternative to free trial, and so on. 

* community currencies, which can be about small circles where there is trust to each other, using fully unbacked offchain cash,
 more complex environments using fully or partially backed cash, potentially with tokenized local reserves (such as gold and silver) 
 etc

Such use cases would definitely win from simple but secure design, no on-chain fees, and no need to work with blockchain 
at all before need to back issued cash or redeem cash for blockchain asssets. 

But there can be more use cases discovered with time!

## Basis Design

As we have offchain cash with possibility to create credit (unbacked money), we have need to track all the money in form 
of IOU (I Owe You) notes issued by an issuer, for all the issuers. In comparison with fully on-chain ChainCash design,
we have to deal with some security relaxation in the case of offchain notes.

As a simple but pretty secure solution, the following design is proposed, which can then be improved in many directions 
(see "Future Extensions" section):

* every participant has a public key over elliptic curve supported by Ergo blockchain (Secp256k1, the same curve is used
 in Bitcoin)
* only reserves are on-chain. A reserve can be created at any time. A reserve is bound to public key of its owner. 
Anyone (presumably, owner in most cases) can top the reserve up.
* for keeping offchain cash ledgers, we have trackers. Anyone can launch a tracker service (just running open-source
 software on top of powerful enough hardware is needed for that). With time a tracker is getting trust and userbase if 
 behaves honestly. The design is trying to minimize trust in tracker. For example, a tracker cant redeem IOU notes made 
 to other parties, as they are signed, and the signature is check in redemption on-chain contract. If tracker is 
 disappearing, after some period last tracker state snapshot committed on-chain becomes redeemable without it. If tracker
  is starting censoring notes associated with a public key, by not including them into on-chain update, it is still
  possible to redeem them. There could be different improvements to the tracker design, see "Future Extensions" section.
* IOU note from A to B is represented as (B_pubkey, amount, timestamp, sig_A) record, where amount is the **total** amount of
 A's debt before B, timestamp is timestamp of latest payment from A to B, and sig_A is a signature for (B_pubkey, amount, 
 nonce). Only one updateable note is stored by a tracker, and redeemable onchain. Thus a tracker is storing 
 (amount, timestamp) pairs for all A->B debt relationships. The tracker commits on-chain to the data by storing a digest 
 of a tree where hash(A ++ B) acts as a key, and (amount, timestamp) acts as a value. 
 
* If A has on-chain reserve, B may redeem offchain from A->B note, by providing proof of (amount, timestamp). Reserve
 contract UTXO is storing tree of hash(AB) -> timestamp pairs. It is impossible to withdraw a note with timestamp <= 
redeemed again. After on-chain redemption, A and B should contact offchain to deduct before next payment from A to B done. 
A note may be redeemed only one week after creation (timestamp of last block is one week ahead of timestamp in the note,
 at least), thus for services it makes sense to have a lot of rotating keys.

## Basis Contract

A basic contract corresponding to the design outlined in the previous section, is available @ [basis.es](basis.es).

## Offchain Logic

## Security Assumptions

We assume that tracker is honestly collecting and announcing notes it has. However, malicious trackers may deviate from
honest behaviour.

Tracker can simply go offline, but then the latest state committed on-chain is still redeemable,

Tracker may remove debt notes of protocol participants. This problem can be tackled with the anti-censorship protection
from "Future Extensions" section.

Tracker may collude with a reserve holder to inject a note with fake timestamp in the past to redeem immediately. 
Tracker would be caught in this case. For making this case impossible with contract, technique similar to anti-censorship 
protection can be used.

## Wallet

## Future Extensions

* Anti-Censorship Protection

If tracker is starting censoring notes associated with a public key, by not including them into on-chain update, it is still
possible to redeem them with anti-censorship protection. For that, tracker box should be protected with a contract which
has condition to include spent tracker input's id into a tree stored in a register. Then tracker is storing commitment to
all it previous states, basically, and we can use that to add a condition to the reserve contract to allow redemption of 
a note which was tracked before but not tracked now, and also not withdrawn. 

* Federated trackers

Instead of a single tracker, we may have federation, like done in Oracle Pools, or double layered federation like done
in Rosen bridge.

* Tracking sidechains

As a continuation of federation tracker idea, we may have tracking sidechains, for example, merged-mined sidechains, to
reduce multisig security to majority-of-Ergo-hashrate-following-sidechain security.

* Programmable cash

We may store redeeming condition script hash instead of recipient pubkey just in IOU notes, and add the condition to 
other redeeming conditions in onchain redemption action.

* Multi-tracker reserve

Possible to have reserve contract with support for multiple reserves, put under AVL+ tree or just in collection if there
 are few of them.

For most reserves that does not make sense probably, but multi-tracker reserves can be used as gateways between 
different trackers, to rebalance liquidity etc. 

* Privacy 

Not hard to do redemptions to stealth addresses. 

## Economy

## Implementation Roadmap

The following implementation plan is targeting catching micropayments in P2P networks, agentic networks, etc ASAP and then 
develop tools for community trading:

* Do tests for Basis contract, like ChainCashSpec or Dexy contracts (Scala)
* Do a token-based variant of reserve contract (ErgoScript)
* Do tracker service (Rust), which is collecting offchain notes and also tracking on-chain reserves, writing 
periodically commitments on chain, informing clients about state of notes / reserves (collateralization etc)
* Do Celaut payment module, where peers can . Add support for agentic layer, so AI agents can buy computations 
over Celaut, then requests to other APIs as well.
* Do a wallet for community trading (maybe in form of telegram bots? like one wallet bot for one community)


## References
