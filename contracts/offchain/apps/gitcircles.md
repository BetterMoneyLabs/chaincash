Git Circles: framework to create currencies for online communities around Open Source projects
=============================================================================================

In this document, we describe Git Circles, a solution to create community currencies for open source project communities.

Basic actions could be seen in the following example:

* Git oracle is posting commit id, its author (the simplest option is to have commits signed w. secp256k1), and number of lines of code in the commit, along wih oracle signature. Via this action new git points are minted, N lines of code provides N git points

* There could be services which are providing services for git points (computing outsourcing services like Celaut, AI services etc)

* One special kind of service is sponsorship, which is about exchanging git points for stablecoins (Gold or USD denominated) / ERG / rsBTC (bridged BTC) etc via https://www.ergoforum.org/t/basis-a-foundational-on-chain-reserve-approach-to-support-a-variety-of-offchain-protocols/5153 . A sponsor may spend git points obtained on services, so may be "sponsorship" would not be the most appropriate term (depending on services economy)

Note, every project (repository or a set of coupled repositories) has its own token. For example, Linux git point is different from Java git point. Potentially Linux and Java ecosystems have different service providers and sponsors. Both systems use Ergo blockchain though.


Some high-level tech details
============================

Components:

* Ergo blockchain - to get from git points to blockchain assets (and back)
* public bulletin board (aka tracker) with events - offchain service to maintain ordered log of offchain events. For starters, it could be a single trusted server. Then it can be replaced with federation, using pod network ( https://pod.network/ ) or a dedicated blockchain for agreement on transactions order. The same server (federation) can be used for Git oracle. Server's signature is used in redemption as well, for federation, as Schnorr signatures as used, MuSig or other aggregated signature schemes could be used for federation approved redemption.

Events:

* Pubkey registration

In both offchain part and on-chain, secp256k1 based pubkeys are used. To bind Github account (e.g. "kushti") with secp256k1 pubkey, a key binding event should be posted, the binding event is about posting Github account along with pubkey and link to public gist published under Github account

* Mint

Git oracle's pubkey is known to everyone. The oracle is tracking master branch of project's repositories. When new commits appear there, done by authors with known pubkeys, oracle is posting minting events.  The oracle is posting commit id, author's pubkey, note value, along with a signature.

There are no rollbacks, so when commits are rolled back with forced push, minted notes are still around. Normally, forced push is not used in more or less established repositories.

* Transfer

After mints, unspent notes do exist in form of (commit id, author's pubkey, note value) are created, and we consider commit id here as unique id of the a note. Then transfer transaction is spending some existing notes, and creating new ones. New notes have the same format (note id, pubkey, note value), where note_id == hash(input note #0's id ++ input note #1's id ++ index), so hash of all the input note ids and also output index. Transfer transaction is signed by each of the input pubkeys, the whole transaction is used as a message.

Structure of transfer transaction is similar to on-chain UTXO transactions.

* Redemption

Redemption transaction is similar to transfer transaction, but output should be signed by pubkey of receiver (sponsor) as well. Signed output allows for on-chain redemption via sastsfying Basis contract conditions ( https://www.ergoforum.org/t/basis-a-foundational-on-chain-reserve-approach-to-support-a-variety-of-offchain-protocols/5153 )


Fees and monetization:


Tech stack:
Ergo node and offchain app for sponsors and redemption
For centralized server, Nostr relay and specialized clients