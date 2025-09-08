{
    // Contract for on-chain reserve (in ERG only for now) backing offchain payments
    // aka Basis
    // https://www.ergoforum.org/t/basis-a-foundational-on-chain-reserve-approach-to-support-a-variety-of-offchain-protocols/5153

    // Main use-cases:
    // * payments for content (such as 402 HTTP code processing)
    // * micropayments
    // * payments in p2p networks
    // * agent-to-agent payments

    // Here are some properties of Basis design:
    // * offchain payments with no need to create anything on-chain first, so possibility to create credit
    // * usage of minimally trusted trackers to track state of mutual debt offchain
    // * onchain contract based redemption with prevention of double redemptions

    // How does that work:
    //  * a tracker holds A -> B debt (as positive number), along with ever increasing (on every operation nonce).
    //    A key->value dictionary is used to store the data as hash(AB) -> (amount, nonce, sig_A), where AB is concatenation of public
    //    keys A and B, "amount" is amount of debt of A before B, nonce is operation nonce, sig_A is signature of A for
    //    A for message (hash(AB), amount, nonce).
    //  * to make a (new payment) to B, A is taking current AB record, increasing debt, signing the updated record and
    //    sending it to the tracker
    //  * tracker is periodically committing to its state (dictionary) by posting its digest on chain
    //  * at any moment it is possible to redeem A debt to B by calling redemption action of the reserve contract below
    //    B -> nonce pair is written into the contract box. Calling the contract after with nonce <= written on is
    //    prohibited. Tracker signature is needed to redeem. On next operation with tracker, debt of A is decreased.
    //    If not, A is refusing to sign updated records. Tracker cant steal A's funds as A's signature is checked.
    //  * if tracker is going offline, possible to redeem without its signature, when at least one week passed
    //  * always possible to top up the reserve


    // Data:
    //  - token #0 - identifying singleton token
    //  - R4 - signing key (as a group element)
    //  - R5 - tree of nonces redeemed (to avoid double spending, it should have insert-only flag set)
    //  - R6 - NFT id of tracker server (bytes) // todo: support multiple payment servers by using a tree
    //
    // Actions:
    //  - redeem note (#0)
    //  - top up      (#1)
    //
    //  Tracker box registers:
    //  - R4 - tracker's signing key
    //  - R5 - commitment to credit data

    val v = getVar[Byte](0).get
    val action = v / 10
    val index = v % 10

    val ownerKey = SELF.R4[GroupElement].get // reserve owner's key
    val selfOut = OUTPUTS(index)

    // common checks for all the paths (not incl. ERG value check)
    val selfPreserved =
            selfOut.propositionBytes == SELF.propositionBytes &&
            selfOut.tokens == SELF.tokens &&
            selfOut.R4[GroupElement].get == SELF.R4[GroupElement].get &&
            selfOut.R6[Coll[Byte]].get == SELF.R6[Coll[Byte]].get

    // todo: do withdrawal with no tracker (one week after)
    if (action == 0) {
      // redemption path

      // tracker box is holding information about debt as AB -> (amt, nonce)
      val tracker = CONTEXT.dataInputs(0)
      val trackerNftId = tracker.tokens(0)._1
      val trackerTree = tracker.R5[AvlTree].get
      val expectedTrackerId = SELF.R6[Coll[Byte]].get
      val trackerIdCorrect = trackerNftId == expectedTrackerId
      val trackerPubKey = tracker.R4[GroupElement].get

      val g: GroupElement = groupGenerator

      val receiver = getVar[GroupElement](10).get // todo : check id
      val receiverBytes = receiver.getEncoded

      val ownerKeyBytes = ownerKey.getEncoded

      val key = blake2b256(ownerKeyBytes ++ receiverBytes)
      
      val reserveSigBytes = getVar[Coll[Byte]](4).get

      val debtAmount = getVar[Long](11).get // todo : check id
      val nonce = getVar[Long](11).get // todo : check id
      val value = longToByteArray(debtAmount) ++ longToByteArray(nonce) ++ reserveSigBytes

      val reserveId = SELF.tokens(0)._1

      val redemptionOut = OUTPUTS(index + 1)
      val redemptionTreeHash = blake2b256(redemptionOut.propositionBytes)
      val afterFees = redemptionOut.value

      val nonceKeyVal = (key, Coll(1.toByte))  // key -> value
      val proof = getVar[Coll[Byte]](2).get
      val nextTree: AvlTree = SELF.R5[AvlTree].get.insert(Coll(nonceKeyVal), proof).get // todo: tree can have insert or update flags
      val properNonceTree = nextTree == selfOut.R5[AvlTree].get // todo: check that the nonce has increased

      val lastBlockTime = CONTEXT.headers(0).timestamp
      val enoughTimeSpent = (nonce > 0) && (lastBlockTime - nonce) > 7 * 86400000 // 7 days in milliseconds passed

      val redeemed = SELF.value - selfOut.value
      val properlyRedeemed = (redeemed <= debtAmount) && enoughTimeSpent

      val message = key ++ longToByteArray(debtAmount) ++ longToByteArray(nonce)

      val trackerSigBytes = getVar[Coll[Byte]](3).get

      val trackerABytes = trackerSigBytes.slice(0, 33)
      val trackerZBytes = trackerSigBytes.slice(33, trackerSigBytes.size)
      val trackerA = decodePoint(trackerABytes)
      val trackerZ = byteArrayToBigInt(trackerZBytes)

      // Computing challenge
      val trackerE: Coll[Byte] = blake2b256(trackerABytes ++ message ++ trackerPubKey.getEncoded) // strong Fiat-Shamir
      val trackerEInt = byteArrayToBigInt(trackerE) // challenge as big integer

      // Signature is valid if g^z = a * x^e
      val properTrackerSignature = (g.exp(trackerZ) == trackerA.multiply(ownerKey.exp(trackerEInt)))

      val reserveABytes = reserveSigBytes.slice(0, 33)
      val reserveZBytes = reserveSigBytes.slice(33, reserveSigBytes.size)
      val reserveA = decodePoint(reserveABytes)
      val reserveZ = byteArrayToBigInt(reserveZBytes)

      // Computing challenge
      val reserveE: Coll[Byte] = blake2b256(reserveABytes ++ message ++ ownerKey.getEncoded) // strong Fiat-Shamir
      val reserveEInt = byteArrayToBigInt(reserveE) // challenge as big integer

      // Signature is valid if g^z = a * x^e
      val properReserveSignature = (g.exp(reserveZ) == reserveA.multiply(ownerKey.exp(reserveEInt)))

      val receiverCondition = proveDlog(receiver)

      sigmaProp(selfPreserved &&
                properNonceTree &&
                properTrackerSignature &&
                properReserveSignature &&
                properlyRedeemed &&
                receiverCondition)
    } else if (action == 1) {
      // top up
      sigmaProp(
        selfPreserved &&
        (selfOut.value - SELF.value >= 1000000000)  && // at least 1 ERG added
        selfOut.R5[AvlTree].get == SELF.R5[AvlTree].get
      )
    } else {
      sigmaProp(false)
    }

}