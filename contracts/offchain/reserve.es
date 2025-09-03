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
    //    A key->value dictionary is used to store the data as AB -> (amount, nonce, sig_A), where AB is concatenation of public
    //    keys A and B, "amount" is amount of debt of A before B, nonce is operation nonce, sig_A is signature of A for
    //    A for message (B, amount, sig_A).
    //  * to make a (new payment) to B, A is taking current AB record, increasing debt, signing the updated record and
    //    sending it to the tracker
    //  * tracker is periodically committing to its state (dictionary) by posting its digest on chain
    //  * at any moment it is possible to redeem A debt to B by calling redemption action of the reserve contract below
    //    B -> nonce pair is written into the contract box. Calling the contract after with nonce <= written on is
    //    prohibited. Tracker signature is needed to redeem. On next operation with tracker, debt of A is decreased.
    //    If not, A is refusing to sign updated records.
    //  * if tracker is going offline, possible to redeem without its signature, when at least one week passed
    //  * always possible to top up the reserve, possible to refund after two weeks of delay



    // Data:
    //  - token #0 - identifying singleton token
    //  - R4 - signing key (as a group element)
    //  - R5 - tree of notes spent (to avoid double spending, it should have insert-only flag set)
    //  - R6 - key of payment server (as a group element) // todo: support multiple payment servers by using a tree
    //  - R7 - refund start height
    //
    // Actions:
    //  - redeem note (#0)
    //  - top up      (#1)

    val v = getVar[Byte](0).get
    val action = v / 10
    val index = v % 10

    val ownerKey = SELF.R4[GroupElement].get // reserve owner's key, used in notes and unlock/lock/refund actions
    val selfOut = OUTPUTS(index)

    // common checks for all the paths (not incl. ERG value check)
    val selfPreserved =
            selfOut.propositionBytes == SELF.propositionBytes &&
            selfOut.tokens == SELF.tokens &&
            selfOut.R4[GroupElement].get == SELF.R4[GroupElement].get &&
            selfOut.R6[GroupElement].get == SELF.R6[GroupElement].get

    if (action == 0) {
      // redemption path

      // tracker box is holding information about set of notes via a tree (AVL+ tree is used)
      // root tree is storing (pk, noteNonce)
      // val tracker = CONTEXT.dataInputs(0)

      val g: GroupElement = groupGenerator

      val reserveId = SELF.tokens(0)._1

      val redemptionOut = OUTPUTS(index + 1)
      val redemptionTreeHash = blake2b256(redemptionOut.propositionBytes)
      val afterFees = redemptionOut.value

      val paymentServerPubKey = SELF.R6[GroupElement].get

      val noteId = getVar[Coll[Byte]](1).get
      val keyVal = (noteId, Coll[Byte](1: Byte))  // key -> value
      val proof = getVar[Coll[Byte]](2).get
      val nextTree: Option[AvlTree] = SELF.R5[AvlTree].get.insert(Coll(keyVal), proof)
      val properTree = nextTree == selfOut.R5[AvlTree].get

      // todo: add min spending height ?

      val redeemed = SELF.value - selfOut.value
      val redeemedBytes = longToByteArray(redeemed)
      val message = redeemedBytes ++ noteId ++ redemptionTreeHash

      val trackerValue = getVar[Coll[Byte]](3).get

      val trackerABytes = trackerValue.slice(0, 33)
      val trackerZBytes = trackerValue.slice(33, trackerValue.size)
      val trackerA = decodePoint(trackerABytes)
      val trackerZ = byteArrayToBigInt(trackerZBytes)

      // Computing challenge
      val trackerE: Coll[Byte] = blake2b256(trackerABytes ++ message ++ paymentServerPubKey.getEncoded) // strong Fiat-Shamir
      val trackerEInt = byteArrayToBigInt(trackerE) // challenge as big integer

      // Signature is valid if g^z = a * x^e
      val properTrackerSignature = (g.exp(z) == a.multiply(ownerKey.exp(trackerEInt)))

      val reserveValue = getVar[Coll[Byte]](4).get

      val reserveABytes = reserveValue.slice(0, 33)
      val reserveZBytes = reserveValue.slice(33, reserveValue.size)
      val reserveA = decodePoint(reserveABytes)
      val reserveZ = byteArrayToBigInt(reserveZBytes)

      // Computing challenge
      val reserveE: Coll[Byte] = blake2b256(reserveABytes ++ message ++ ownerKey.getEncoded) // strong Fiat-Shamir
      val reserveEInt = byteArrayToBigInt(reserveE) // challenge as big integer

      // Signature is valid if g^z = a * x^e
      val properReserveSignature = (g.exp(z) == a.multiply(ownerKey.exp(reserveEInt)))

      sigmaProp(selfPreserved && properTree && properTrackerSignature && properReserveSignature)
    } else if (action == 1) {
      // top up
      sigmaProp(
        selfPreserved &&
        (selfOut.value - SELF.value >= 1000000000)  && // at least 1 ERG added
        selfOut.R5[AvlTree].get == SELF.R5[AvlTree].get
      )
    } else if (action == 2) {
      // init refund
      val correctHeight = selfOut.R7[Int].get >= HEIGHT - 5
      val correctValue = selfOut.value >= SELF.value
      // todo: recheck registers preservation
      sigmaProp(selfPreserved && correctHeight && correctValue) && proveDlog(ownerKey)
    } else if (action == 3) {
      // cancel refund
      val correctHeight = !(selfOut.R7[Int].isDefined)
      val correctValue = selfOut.value >= SELF.value
      // todo: recheck registers preservation
      sigmaProp(selfPreserved && correctHeight && correctValue) && proveDlog(ownerKey)
    } else if (action == 4) {
      // complete refund
      val refundStartHeight = SELF.R5[Int].get
      val refundNotificationPeriod = 14400 // 20 days
      val correctHeight = (refundStartHeight + refundNotificationPeriod) <= HEIGHT
      // todo: recheck registers preservation
      sigmaProp(selfPreserved && correctHeight && correctValue) && proveDlog(ownerKey) // todo: check is it ok to check no other conditions
    } else {
      sigmaProp(false)
    }

}