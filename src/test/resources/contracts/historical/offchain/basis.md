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
 A's debt before B, timestamp is the timestamp of latest payment from A to B (in milliseconds since Unix epoch),
 and sig_A is a signature for (B_pubkey, amount, timestamp). Only one updateable note is stored by a tracker,
 and redeemable onchain. Thus a tracker is storing (amount, timestamp) pairs for all A->B debt relationships.
 The tracker commits on-chain to the data by storing a digest of a tree where hash(A ++ B) acts as a key,
 and (amount, timestamp) acts as a value.

* If A has on-chain reserve, B may redeem offchain from A->B note, by providing proof of (amount, timestamp).
 Reserve contract UTXO is storing tree of hash(ownerKey||receiverKey) -> (timestamp, cumulativeRedeemedAmount) pairs.
 The value format is: timestamp (8 bytes big-endian) ++ cumulativeRedeemedAmount (8 bytes big-endian) = 16 bytes total.
 During redemption, the contract verifies that the note's timestamp is **greater than** the stored timestamp,
 which prevents replay attacks with old notes. After on-chain redemption, A and B should contact offchain tracker
 to update their records before next payment from A to B is done.

* Debt Transfer (Triangular Trade): The protocol supports transferring debt between creditors with debtor consent.
 Example: A owes 10 ERG to B. B wants to buy from C for 5 ERG. Instead of on-chain redemption:
  1. B requests A to sign new notes: A->B (5 ERG remaining), A->C (5 ERG transferred)
  2. A signs both notes, tracker signs both notes
  3. Old note A->B (10 ERG) is cancelled, new notes A->B (5 ERG) and A->C (5 ERG) are created
  4. C can now redeem A->C note from A's reserve
 This enables efficient multi-party settlements without on-chain transactions.

## Basis Contract

A basic contract corresponding to the design outlined in the previous section, is available @ [basis.es](basis.es).

**Reserve Owner Refund (Exit):** The reserve owner can unilaterally exit without tracker or creditor
cooperation, protecting the owner from censorship. To protect creditors from the owner silently draining
collateral, refund is two-phase:

1. **Initiate refund (action #2):** the owner signs a transaction setting register R7 to the initiation
   height (current or slightly future-dated, to tolerate delayed block inclusion; backdating is rejected).
   One-shot only: re-initiation is not allowed.
2. **Complete refund (action #3):** after a waiting period of 43200 blocks (~2 months), the owner signs a
   transaction spending the reserve box and taking all funds and tokens.

Redemptions and top-ups remain fully enabled during and after the waiting period (both preserve R7), so
creditors have ~2 months to redeem their notes before the owner can withdraw. Redemption is also not
disabled after the deadline, so an owner who initiated but never completed the refund does not freeze the
reserve. Only full withdrawal is supported: a partial refund would require tracker attestation of
outstanding debt, reintroducing the censorship vector the refund protects against.

Wallets and trackers should monitor reserves for R7 being set and notify creditors; acceptance predicates
should reject new notes backed by reserves with a pending refund.

**Compiled Contract Address (P2S):**
```
3PQnJ92Krn6NeM1GdMSmNayw34Nuud7UKMoKSTRUTucsNybh99K1HEfjZqyvP7cPag1yBkDv3ruMAgb2NsVKq3tAygjHz7mKDzHK6CJGhD3WfNViD7DoViqbgsXrzvs6Kt8Wyzb48uGqJAFQFWes6ZPKELqUZowy8xtVCS5w1VwnyaeRiWpEyUVGaEHw3qWo5DcVxzmMAP8XXhVTw1rYYrUxsyGPNaBxQkkkTVD9L3bmw77EfeAJgJ1hLxghykNofHscHtMtES4v5FSfqke3Huun81S7gNoraEnsR6Dy6YnQgrBswwCZhyGc89YeNFQn1TCFh5Hct3nKGrd1bV5zoCw67Q9fKtoaCtvcPQ2GDWycGKNRNgyAnPEa8WbHbTEVcjAN25aBwhnY5LFGqYxnUAjhpfkTPJ4FJWRijSqMESzpyrmhTLZdivmn4YSwcchVZr7bHGbfncEDwqPKefdoxNnVPxuVdmeqQXL3aDL7TaqWgExzz1UPXHw3UiKYTUkNgQKCN4WV3LHqc9PecoisL77ydVbSCxPapaX2zTf26F8bGK3hsTVBZnMkt93SJP5GmPgZU5FT9NkFh4okjXK9ce2wmA4MV93ySyYnUKGwTRFJWwE7G1MYqBqTY3ESkn8PJHqVuL4cgtuV2GEPagKt19befRAuUV3FaLGVPJMzpKdANd7hKGZRcy3DnPfT1Q9dyFD4VpdBgFRXJWaaDqYjL7ni4nJcKKam9P395wRRnjGWhTV4hv3KoxC8Xk2CZAUjhkTzvuNHxQrLsWjyrKWJqZgs2uZxoAEHEobDegYWiTcnFCPU9EeJxZLSjysDFninqpQvA66Yt1SvJnSZm49RKsaoR98UJVScdiQfNZE76zTYBioXGatdRz7QVkXDzDPjPMu9Hhepc2XbHqo3ia8tszHptbnSzm2R3PC7iu2Tnhu3QT
```

## Basis-Token Contract (Token-Based Reserve)

A variant of the Basis contract that uses custom tokens instead of ERG for reserve backing is available @ [basis-token.es](basis-token.es).
It supports the same two-phase reserve owner refund as the Basis contract (see "Reserve Owner Refund (Exit)" above):
on completion the owner takes both the reserve tokens and the ERG, destroying the reserve box.

**Compiled Contract Address (P2S):**
```
Qk8QohdMELkLdDT7w69gnXc1NhcmJAoLZosQQdfvYmUy4Dig8zThWGUw2gQeWsjqQrepvbXM9TkLLNSsuZz5rHYhKEemXQNkARCa8eDg91n9HpxMsG36isUri3tCocfrMujbbqt6apGc6LdU2a55SSyBzYoSLEPAG27e29XdnBCdsTza6aDPrD2Q3H4GubdcSog6Ziz7Ra934LSyy1gULxHvh11UNys2Lac4Hg6fpMbsXLnJHpit4mRymbdGYDTcxxW4aFJMPKeSpihjSNMJ1LVYXqDR4Nn5PWP6f3BuJvKFa8WfcFz2yDYM3JBzyntnu6nGHifBDctuns7NYV32zRb14ndr9MwprdtybQJdhaJPeWNz2pynHSQUREP8FdDUfefLKnTFsJfv3mVzxTiohuNYm48wAf9PburEQpSCZTiYDRfJq6oupWqjj6yFzdmR8FsTQ5jA8GEQus4146MmTYpHaiMhzrhK9ofzL7N8sMhEGoEVHnWoyFbX9kkyf5RfCFZFkwkKGLXsjt6dyENiqbJDnXhqbc9NuFmNt8k8cTt6tMSUn6F52gEeFJA8XoMxrKVq6k6bkpdsbT7AKgtKHjMUwZE6zp8Zb9sJDuE39BVKPjgbgDEchDwDydW6ZwbTVxnGEww67aqskmN3PYTwENEuMmgoWGSsSdLxGipexeiM7EYYhWK6i9h7rkK8tVeAHVC6VSavissdas9Nb9G74cECN9BYTQi8yTuXwnVsoJ9eW5P5TWUgYm8DxuCwgKyNWEiAzemKwuBXPveuW3SJcyBkP7U53hR2m3ohkc99DMQ93y4eNneUVkogu7RzXWVRKX41Vqfpq8BGpQpQB6fBasU5J2shkGikbGi1c2DqdaVqFi5zbCGZCTXXFjHsvj8KrKJbzZsh5E3ea5eB2GYQ2JQTfahydzsqEduTPzJb8ka5yfVpenk57JJd9sn7dcX3KDVpcBBr5cpx9b2P4AcRvEioUjEZHBbrALAXgemSMfmay47UndCA6tDPTF13hpVHGe5jBKuWyD4YNVgdBJtPAKuXj4EKnyF8DWzuerM5LH9GAJ2behPusQ
```

## Offchain Logic

### Tracker

Tracker is publishing following events via NOSTR protocol as relay:

* note - new or updated note, along with proof of tracker state transformation and digest after operation
* redemption - redemption done from a reserve
* reserve top-up
* commitment - posting data for on-chain tracker state commitment update (header, proof of UTXO against header, UTXO with commitment)
* 80% alert - tracker is posting it when debt level of some pubkey reaching 80% of collateral 
* 100% alert - tracker is posting it when debt level of some pubkey reaching 100% of collateral

Then it also supports following API requests which can be run separately from relay potentially:

* getNotesForKey - returns all the notes sssociated with a pubkey
* getProof - get proof for a note against latest digest published by the tracker (not necessarily committed on-chain)
* getKeyStatus - returns current collateralization of a pubkey along with other important information. Useful for light
wallets and clients which are ready 
* POST noteUpdate - create or update a note

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

* Anti-Collusion Protection

Let's suppose that, at time t1, we have:

(Bob -> Alice, 2, 9), with the 9th note signed by Bob.

And, at time t2, we have:

(Bob -> Alice, 3, 10), with the 10th note signed by Bob.

Bob (at least, he incentivized to) informing tracker, and the tracker commits on-chain 
the latest nonce seen. Also, tracker's signature is required for normal redemption.

So at the moment t2:

1) if committed state is (Bob -> Alice, 3, 10) , Alice can't withdraw (Bob -> Alice, 2, 9)
2) if committed state is (Bob -> Alice, 3, 9) , Alice can withdraw by colluding with the tracker , 
    and the misbehavior has onchain footprint

Possible to introduce protection from the collusion by making debt amount ever increasing (so then it is amount of 
offchain debt of Bob before Alice, including redeemed), and  storing redeemed amount in Bob's reserve contract as well.

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
* Do Celaut payment module, where peers can set credit limits and pay each other. Add support for agentic layer, so AI agents can buy computations 
over Celaut, then requests to other APIs as well.
* Do showcase for agent-to-agent payments
* Do a wallet for community trading (maybe in form of telegram bots? like one wallet bot for one community)
* Do alternative for NOSTR zaps

and so on

## References
