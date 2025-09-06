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
Anyone (presumably, owner in most cases) can top the reserve up. Owner can withdraw any amount from the reserve, 
in two steps (on-chain transactions), first, the owner is announcing that he is going to withdraw, and two weeks after 
the withdrawal may be completed (or cancelled at any time).
* for keeping offchain cash ledgers, we have trackers. Anyone can launch a tracker service (just running open-source
 software on top of powerful enough hardware is needed for that). With time a tracker is getting trust and userbase if 
 behaves honestly. The design is trying to minimize trust in tracker.


## Basis Contract

## Basis Offchain Logic

## Wallet

## Future Extensions

* Programmable cash
* Multi-tracker reserve

## Economy

## References
