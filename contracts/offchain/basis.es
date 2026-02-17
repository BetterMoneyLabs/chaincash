{
    // Contract for on-chain reserve (in ERG only for now) backing offchain payments
    // aka Basis
    // https://www.ergoforum.org/t/basis-a-foundational-on-chain-reserve-approach-to-support-a-variety-of-offchain-protocols/5153

    // Main use-cases:
    // * digital payments with credit creation allowed
    // * especially with areas with no stable Inernet connection (over mesh networks)
    // * agent-to-agent payments
    // * payments for content (such as 402 HTTP code processing)
    // * micropayments
    // * payments in p2p networks


    // Here are some properties of Basis design:
    // * offchain payments with no need to create on-chain reserves first, so possibility to create credit
    // * only minimally trusted trackers to track state of mutual debt offchain used, with no possibility to steal funds etc
    // * onchain contract based redemption with prevention of double redemptions

    // How does that work:
    //  * a tracker holds ever created A -> B debt (as positive ever increasing number).
    //    A key->value dictionary is used to store the data as hash(AB) -> (amount, timestamp, sig_A), where AB is concatenation of public
    //    keys A and B, "amount" is amount of debt of A before B, timestamp is operation timestamp (in milliseconds), sig_A is signature of A for
    //    A for message (hash(AB), amount, timestamp).
    //  * to make a (new payment) to B, A is taking current AB record, increasing debt, signing the updated record and
    //    sending it to the tracker
    //  * tracker is periodically committing to its state (dictionary) by posting its digest on chain
    //  * at any moment it is possible to redeem A debt to B by calling redemption action of the reserve contract below
    //    The contract tracks cumulative amount of debt already redeemed for each (owner, receiver) pair in an AVL tree.
    //    Tracker signature is needed to redeem normally.
    //  * if tracker is going offline, possible to redeem without its signature, when at least 3 days passed since tracker creation
    //    (NOTE: this affects ALL debts associated with the tracker simultaneously)
    //  * always possible to top up the reserve, to redeem, reserve holder is making an offchain payment to self (A -> A)
    //    and then redeem

    // Security analysis and the role of the tracker:
    //  * the usual problem is than A can pay to B and then create a note from A to self and redeem. Solved by tracker solely.
    //  * double spending of a note is not possible by contract design

    // Normal workflow:
    // * A is willing to buy some services for B. He is asking B whether collateral (along with debt notes)
    //     can be accepted (can be done non-interactively when B is publishing his acceptance predicated)
    // * if A's debt note would be accepted, he is signing a debt note and sending it to a tracker. Tracker is providing
    //   a signature on a bit modified message to be used in case of tracker going offline. A is sending debt note and
    //   both signatures to B.
    // * after some time (defined by offchain logic), B can redeem, fully or partially. For that, B is contacting tracker
    //   to obtain a signature on debt note to present then A's and tracker's signatures along with the debt note to a contract
    // * at any time A can make another payment to B, by signing a message with increased cumulative debt amount
    // * if tracker is going offline, B can redeem using a special signature from tracker from above. Ideally, tracker
    //   shouldnt' allow for another redemption before B's redemption transaction got confirmed on-chain, or some deadline,
    //   whatever comes first
    // * A can refund by redeeming like B. Actually, in pseudonymous environment it always impossible to say how many
    //   alts A may have. So B should always track collateralization level. B can prepare redemption transaction in advance and
    //   ask 3rd party service to submmit it when A is offline.

    // Tracker's role here is to guarantee fairness of payments. Tracker can't steal A's onchain funds as A's signature is
    // required. Tracker can re-order redemption trandactions though, thus affecting outcome for B when a note is
    // undercollateralized (this can be improved). Ideally, tracker should  Tracker can be centralized entity or federation.

    // Debt notes are not transferrable in current design. So B cant' really pay C with a debt note issued by A. B has
    // to create a new debt note.

    // With some trust involved in managing redemption process, some pros are coming :
    // * no on-chain fees. Suitable for micropayments.
    // * Unlike other offchain cash schemes (Lightning, Cashu/Fedimint etc), transaction can be done with no
    //   collateralization. Or first there could be payment and then on-chain reserve being created
    //   to pay for services already provided. Could provide nice alternative to free trials etc.

    // Demos:
    //

    // Possible extensions:

    // Data:
    //  - R4 - signing key (as a group element)
    //  - R5 - tree of debt redeemed
    //  - R6 - NFT id of tracker server (bytes) // todo: support multiple payment servers by using a tree
    //
    // Actions:
    //  - redeem note (#0)
    //  - top up      (#1)
    //
    //  Tracker box registers:
    //  - R4 - tracker's signing key

    // action and reserve output index. By passing them instead of hard-coding, we allow for multiple notes to be
    // redeemed at once, which can be used for atomic mutual debt clearing etc
    val v = getVar[Byte](0).get
    val action = v / 10
    val index = v % 10 // reserve output position

    val ownerKey = SELF.R4[GroupElement].get // reserve owner's key
    val selfOut = OUTPUTS(index)

    // common checks for all the paths (not incl. ERG value and R5 check)
    val selfPreserved =
            selfOut.propositionBytes == SELF.propositionBytes &&
            selfOut.tokens == SELF.tokens &&
            selfOut.R4[GroupElement].get == SELF.R4[GroupElement].get &&
            selfOut.R6[Coll[Byte]].get == SELF.R6[Coll[Byte]].get

    if (action == 0) {
      // redemption path
      // context extension variables used:
      // #1 - receiver pubkey (as a group element)
      // #2 - reserve owner's signature for the debt record
      // #3 - current total debt amount
      // #5 - proof for insertion into reserve's AVL+ tree
      // #6 - tracker's signature
      // #7 - [OPTIONAL] proof for AVL+ tree lookup for lender-borrower pair

      // Base point for elliptic curve operations
      val g: GroupElement = groupGenerator

      // Tracker box holds the debt information as key-value pairs: AB -> amount
      val tracker = CONTEXT.dataInputs(0) // Data input: tracker box containing debt records
      val trackerNftId = tracker.tokens(0)._1 // NFT token ID identifying the tracker
      val trackerPubKey = tracker.R4[GroupElement].get // Tracker's public key for signature verification
      val expectedTrackerId = SELF.R6[Coll[Byte]].get // Expected tracker ID stored in reserve contract

      // Verify that tracker identity matches
      val trackerIdCorrect = trackerNftId == expectedTrackerId

      // Receiver of the redemption (creditor)
      val receiver = getVar[GroupElement](1).get
      val receiverBytes = receiver.getEncoded // Receiver's public key bytes

      val ownerKeyBytes = ownerKey.getEncoded // Reserve owner's public key (from R4 register) bytes

      // Create key for debt record: hash(ownerKey || receiverKey)
      // the key is used in the reserve's tree stored in R5 register
      val key = blake2b256(ownerKeyBytes ++ receiverBytes)

      // Reserve owner's signature for the debt record
      val reserveSigBytes = getVar[Coll[Byte]](2).get

      val totalDebt = getVar[Long](3).get

      val lookupProofOpt = getVar[Coll[Byte]](7)
      val redeemedDebt = if(lookupProofOpt.isDefined){
        val redeemedDebtBytes = SELF.R5[AvlTree].get.get(key, lookupProofOpt.get).get
        byteArrayToLong(redeemedDebtBytes)
      } else {
        0L
      }

      // Check if enough time has passed for emergency redemption (without tracker signature)
      // NOTE: All debts associated with this tracker (both new and old) become eligible
      // for emergency redemption simultaneously after 3 days from tracker creation
      val trackerUpdateTime = tracker.creationInfo._1
      val enoughTimeSpent = (HEIGHT - trackerUpdateTime) > 3 * 720 // 3 days passed

      // Message to verify signatures: key || total debt, in case of emergency exit key || total debt || 0
      val message = if (enoughTimeSpent) {
         key ++ longToByteArray(totalDebt) ++ longToByteArray(0L)
      } else {
         key ++ longToByteArray(totalDebt)
      }

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

      // Calculate amount being redeemed and verify it doesn't exceed debt
      val redeemed = SELF.value - selfOut.value
      val debtDelta = (totalDebt - redeemedDebt)
      val properlyRedeemed = (redeemed > 0) && (redeemed <= debtDelta) && properTrackerSignature

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

      val newRedeemed = redeemedDebt + redeemed
      val treeValue = longToByteArray(newRedeemed)
      val redeemedKeyVal = (key, treeValue)  // key -> redeemed debt value
      val insertProof = getVar[Coll[Byte]](5).get // Merkle proof for tree insertion
      val nextTree: AvlTree = SELF.R5[AvlTree].get.insert(Coll(redeemedKeyVal), insertProof).get // todo: insertOrUpdate?
      // Verify tree was properly updated in output
      val properRedemptionTree = nextTree == selfOut.R5[AvlTree].get

      // Verify receiver's signature on transaction bytes
      val receiverCondition = proveDlog(receiver)

      // Combine all validation conditions
      sigmaProp(selfPreserved &&
                trackerIdCorrect &&
                properRedemptionTree &&
                properReserveSignature &&
                properlyRedeemed &&
                receiverCondition)
    } else if (action == 1) {
      // top up
      sigmaProp(
        selfPreserved &&
        selfOut.R5[AvlTree].get == SELF.R5[AvlTree].get && // as R5 register preservation is not checked in selfPreserved
        (selfOut.value - SELF.value >= 100000000) // at least 0.1 ERG added
      )
    } else {
      sigmaProp(false)
    }

}