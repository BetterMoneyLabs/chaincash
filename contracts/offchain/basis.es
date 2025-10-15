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
    //  * a tracker holds A -> B debt (as positive number), along with ever increasing (on every operation) timestamp.
    //    A key->value dictionary is used to store the data as hash(AB) -> (amount, timestamp, sig_A), where AB is concatenation of public
    //    keys A and B, "amount" is amount of debt of A before B, timestamp is operation timestamp (in milliseconds), sig_A is signature of A for
    //    A for message (hash(AB), amount, timestamp).
    //  * to make a (new payment) to B, A is taking current AB record, increasing debt, signing the updated record and
    //    sending it to the tracker
    //  * tracker is periodically committing to its state (dictionary) by posting its digest on chain
    //  * at any moment it is possible to redeem A debt to B by calling redemption action of the reserve contract below
    //    B -> timestamp pair is written into the contract box. Calling the contract after with timestamp <= written on is
    //    prohibited. Tracker signature is needed to redeem. On next operation with tracker, debt of A is decreased.
    //    If not, A is refusing to sign updated records. Tracker cant steal A's funds as A's signature is checked.
    //  * if tracker is going offline, possible to redeem without its signature, when at least one week passed
    //  * always possible to top up the reserve, to redeem, reserve holder is making an offchain payment to self (A -> A)
    //    and then redeem


    // Data:
    //  - token #0 - identifying singleton token
    //  - R4 - signing key (as a group element)
    //  - R5 - tree of timestamps redeemed (to avoid double spending, it should have insert-only flag set)
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

      // Tracker box holds the debt information as key-value pairs: AB -> (amount, timestamp)
      val tracker = CONTEXT.dataInputs(0) // Data input: tracker box containing debt records
      val trackerNftId = tracker.tokens(0)._1 // NFT token ID identifying the tracker
      val trackerTree = tracker.R5[AvlTree].get // AVL tree storing debt commitments from tracker
      val expectedTrackerId = SELF.R6[Coll[Byte]].get // Expected tracker ID stored in reserve contract
      val trackerIdCorrect = trackerNftId == expectedTrackerId // Verify tracker identity matches
      val trackerPubKey = tracker.R4[GroupElement].get // Tracker's public key for signature verification

      val g: GroupElement = groupGenerator // Base point for elliptic curve operations

      // Receiver of the redemption (creditor)
      val receiver = getVar[GroupElement](1).get
      val receiverBytes = receiver.getEncoded // Receiver's public key bytes

      val ownerKeyBytes = ownerKey.getEncoded // Reserve owner's public key bytes

      // Create key for debt record: hash(ownerKey || receiverKey)
      val key = blake2b256(ownerKeyBytes ++ receiverBytes)
      
      // Reserve owner's signature for the debt record
      val reserveSigBytes = getVar[Coll[Byte]](2).get

      // Debt amount and timestamp from the debt record
      val debtAmount = getVar[Long](3).get
      val timestamp = getVar[Long](4).get
      val value = longToByteArray(debtAmount) ++ longToByteArray(timestamp) ++ reserveSigBytes

      val reserveId = SELF.tokens(0)._1 // Reserve singleton token ID

      // Output box where redeemed funds are sent
      val redemptionOut = OUTPUTS(index + 1)
      val redemptionTreeHash = blake2b256(redemptionOut.propositionBytes)
      val afterFees = redemptionOut.value

      // Update timestamp tree to prevent double redemption
      // Store timestamp to mark it as redeemed
      val timestampKeyVal = (key, longToByteArray(timestamp))  // key -> timestamp value
      val proof = getVar[Coll[Byte]](5).get // Merkle proof for tree insertion
      // Insert redeemed timestamp into AVL tree
      val nextTree: AvlTree = SELF.R5[AvlTree].get.insert(Coll(timestampKeyVal), proof).get // todo: tree can have insert or update flags
      // Verify tree was properly updated in output
      val properTimestampTree = nextTree == selfOut.R5[AvlTree].get // todo: check that the timestamp has increased

      // Message to verify signatures: key || amount || timestamp
      val message = key ++ longToByteArray(debtAmount) ++ longToByteArray(timestamp)

      // Tracker's signature authorizing the redemption
      val trackerSigBytes = getVar[Coll[Byte]](6).get

      // Split tracker signature into components (Schnorr signature: (a, z))
      val trackerABytes = trackerSigBytes.slice(0, 33) // Random point a
      val trackerZBytes = trackerSigBytes.slice(33, trackerSigBytes.size) // Response z
      val trackerA = decodePoint(trackerABytes) // Decode random point
      val trackerZ = byteArrayToBigInt(trackerZBytes) // Convert response to big integer

      // Compute challenge for tracker signature verification (Fiat-Shamir)
      val trackerE: Coll[Byte] = blake2b256(trackerABytes ++ message ++ trackerPubKey.getEncoded) // strong Fiat-Shamir
      val trackerEInt = byteArrayToBigInt(trackerE) // challenge as big integer

      // Verify tracker Schnorr signature: g^z = a * x^e
      val properTrackerSignature = (g.exp(trackerZ) == trackerA.multiply(trackerPubKey.exp(trackerEInt)))

      // Check if enough time has passed for emergency redemption (without tracker signature)
      val lastBlockTime = CONTEXT.headers(0).timestamp
      val enoughTimeSpent = (timestamp > 0) && (lastBlockTime - timestamp) > 7 * 86400000 // 7 days in milliseconds passed

      // Calculate amount being redeemed and verify it doesn't exceed debt
      val redeemed = SELF.value - selfOut.value
      val properlyRedeemed = (redeemed <= debtAmount) && (enoughTimeSpent || properTrackerSignature)

      // Split reserve owner signature into components (Schnorr signature: (a, z))
      val reserveABytes = reserveSigBytes.slice(0, 33) // Random point a
      val reserveZBytes = reserveSigBytes.slice(33, reserveSigBytes.size) // Response z
      val reserveA = decodePoint(reserveABytes) // Decode random point
      val reserveZ = byteArrayToBigInt(reserveZBytes) // Convert response to big integer

      // Compute challenge for reserve signature verification (Fiat-Shamir)
      val reserveE: Coll[Byte] = blake2b256(reserveABytes ++ message ++ ownerKey.getEncoded) // strong Fiat-Shamir
      val reserveEInt = byteArrayToBigInt(reserveE) // challenge as big integer

      // Verify reserve owner Schnorr signature: g^z = a * x^e
      val properReserveSignature = (g.exp(reserveZ) == reserveA.multiply(ownerKey.exp(reserveEInt)))

      // Verify receiver's proposition (creditor must be able to spend the redemption output)
      val receiverCondition = proveDlog(receiver)

      // Combine all validation conditions
      sigmaProp(selfPreserved &&
                properTimestampTree &&
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