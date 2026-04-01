{
    // Contract for on-chain reserve (in custom tokens) backing offchain payments
    // aka Basis with Token Reserve
    // Similar to basis.es but uses custom tokens instead of ERG for reserve backing
    // Notes are denominated in the same token as the reserve

    // Main use-cases:
    // * digital payments with credit creation allowed using custom tokens
    // * token-based mutual credit systems
    // * agent-to-agent payments in specific token ecosystems
    // * micropayments with custom tokens
    // * payments in p2p networks with native tokens

    // How does that work:
    //  * a tracker holds cumulative A -> B debt (as positive ever increasing number).
    //    A key->value dictionary is used to store the data as hash(A_pubkey || B_pubkey) -> totalDebt,
    //    where totalDebt is the cumulative amount of debt A owes to B (in token units).
    //  * to make a (new payment) to B, A is taking current AB record, increasing cumulative debt,
    //    signing the updated record (message: hash(A||B) || totalDebt || timestamp) and sending it to the tracker.
    //    The timestamp is in milliseconds since Unix epoch (Java time format).
    //  * tracker is periodically committing to its state (dictionary) by posting its digest on chain
    //    via an AVL tree in register R5. The tree stores hash(A||B) -> totalDebt mappings.
    //  * at any moment it is possible to redeem A's debt to B by calling redemption action of the reserve contract below.
    //    The contract tracks cumulative amount of debt already redeemed for each (owner, receiver) pair in an AVL tree.
    //    The AVL tree stores: hash(ownerKey||receiverKey) -> (timestamp, cumulativeRedeemedAmount)
    //    where timestamp is the latest payment timestamp and cumulativeRedeemedAmount is total redeemed.
    //    Redemption requires BOTH reserve owner's signature AND tracker's signature on message:
    //    hash(ownerKey||receiverKey) || totalDebt || timestamp.
    //    The tracker signature guarantees that the offchain state is consistent and prevents double-spending.
    //    Additionally, the contract verifies that totalDebt is committed in the tracker's AVL tree (context var #8 provides lookup proof).
    //    The contract also verifies that the note's timestamp is greater than any previously redeemed timestamp,
    //    preventing replay attacks with old notes.
    //  * to redeem: B contacts tracker to obtain signature on the debt note, then presents reserve owner's signature
    //    (from original IOU note) and tracker's signature to the on-chain contract along with AVL tree proofs:
    //    - proof for reserve tree lookup (context var #7, optional for first redemption)
    //    - proof for tracker tree lookup (context var #8, required)
    //  * always possible to top up the reserve. To redeem partially, reserve holder can make an offchain payment to self (A -> A)
    //    updating the cumulative debt, then redeem the desired amount.
    //
    // Debt Transfer (Novation):
    //  * The scheme supports transferring debt obligations between creditors with debtor consent.
    //  * Example: A owes debt to B. B wants to buy from C. If A agrees, A can sign new notes:
    //    - Note 1: A -> B for remaining amount (e.g., 5 tokens)
    //    - Note 2: A -> C for transferred amount (e.g., 5 tokens)
    //  * Process:
    //    1. B initiates transfer: requests payment from C via A's debt
    //    2. A signs two new notes: one to B (remaining), one to C (transferred)
    //    3. Tracker signs both new notes
    //    4. Tracker updates ledger: cancels old A->B note, adds new A->B and A->C notes
    //  * This allows debt to circulate in the network: A's credit with B can be used to pay C,
    //    effectively making debt notes transferrable with debtor consent.
    //  * Benefits:
    //    - Enables triangular trade: A->B->C becomes A->C (B is paid by debt transfer)
    //    - Reduces need for on-chain redemption: debt can be re-assigned offchain
    //    - Maintains security: debtor must sign new notes, tracker must verify and commit

    // Security analysis and the role of the tracker:
    //  * the usual problem is that A can pay to B and then create a note from A to self and redeem. Solved by tracker solely.
    //  * double spending of a note is not possible by contract design (AVL tree tracks cumulative redeemed amounts).
    //  * tracker cannot steal funds as both owner and tracker signatures are required for redemption.
    //  * tracker can re-order redemption transactions, potentially affecting outcome for undercollateralized notes.
    //  * debt transfer security:
    //    - debtor (A) must sign new notes: prevents unauthorized transfer of debt obligation
    //    - tracker verifies and signs: prevents creating notes without tracker approval
    //    - both signatures required: neither debtor nor tracker can act alone
    //    - timestamp verification: prevents replay attacks with old notes

    // Normal workflow:
    // * A is willing to buy some services from B. A asks B whether debt notes (IOU) are accepted as payment.
    //   This can be done non-interactively if B publishes their acceptance predicate.
    // * If A's debt note is acceptable, A creates an IOU note with cumulative debt amount and signs it
    //   (signature on message: hash(A_pubkey || B_pubkey) || totalDebt || timestamp).
    //   The timestamp is in milliseconds since Unix epoch (Java time format).
    //   A sends the note to the tracker.
    // * The tracker verifies the note against its state, updates its internal ledger, and provides a signature
    //   on the same message. This tracker signature is required for on-chain redemption.
    // * A sends both signatures (A's and tracker's) to B. B now holds a valid, redeemable IOU note.
    // * At any time, B can redeem the debt by presenting both signatures to the reserve contract along with
    //   an AVL tree proof showing the cumulative redeemed amount. The contract verifies both signatures and
    //   ensures the redeemed amount doesn't exceed (totalDebt - alreadyRedeemed).
    //   The contract also verifies timestamp > storedTimestamp, preventing replay attacks.
    // * At any time, A can make another payment to B by signing a message with increased cumulative debt amount
    //   and new timestamp.
    // * A can refund by redeeming like B (in pseudonymous environments, A may have multiple keys).
    //   B should always track collateralization level and can prepare redemption transactions in advance.
    //
    // Debt Transfer Workflow (Triangular Trade):
    // * Scenario: A owes 10 tokens to B. B wants to buy 5 tokens worth of services from C.
    // * Step 1: B proposes to C that B will pay via debt transfer from A. C agrees.
    // * Step 2: B requests payment from A: sign new notes for remaining debt and transferred debt.
    // * Step 3: A signs two new notes:
    //   - Note 1: A -> B for 5 tokens (remaining debt)
    //   - Note 2: A -> C for 5 tokens (transferred debt)
    // * Step 4: Tracker signs both new notes
    // * Step 5: Tracker updates ledger: cancels old A->B (10 tokens), adds A->B (5 tokens) and A->C (5 tokens)
    // * Result: B is paid (debt reduced), C is creditor (new note received), A owes C instead of part of B.
    // * C can now redeem from A's reserve or further transfer the debt to D (with A's consent).

    // Tracker's role here is to guarantee fairness of payments. Tracker can't steal A's onchain funds as A's signature is
    // required. Tracker cannot enable double-redemption as the contract tracks cumulative redeemed amounts in AVL tree.
    // Tracker can re-order redemption transactions though, potentially affecting outcome for B when a note is
    // undercollateralized. Tracker can be a centralized entity or a federation.

    // There could be a lot of trackers around the world. Some could be global, some serving local trade. Thus the whole
    // system could be seen as a network of different tracker-centered networks, with Ergo blockchain being a neutral
    // global trustless financial layer connecting different networks.

    // With some trust involved in managing redemption process, some pros are coming:
    // * no on-chain fees for offchain transactions. Suitable for micropayments.
    // * Unlike other offchain cash schemes (Lightning, Cashu/Fedimint etc), transactions can be done with no
    //   collateralization first. Or there could be payment on credit and then on-chain reserve being created
    //   to pay for services already provided. Could provide nice alternative to free trials etc.

    // Data:
    //  - token #1 - reserve token (the custom token used for reserve backing)
    //  - R4 - signing key (as a group element)
    //  - R5 - AVL tree tracking redeemed debt and timestamp per (owner, receiver) pair
    //         stores: hash(ownerKey || receiverKey) -> (timestamp, cumulativeRedeemedAmount)
    //         where timestamp is the latest payment timestamp (Long, 8 bytes big-endian)
    //         and cumulativeRedeemedAmount is the total amount redeemed (Long, 8 bytes big-endian)
    //         Value format: timestamp (8 bytes) ++ redeemedAmount (8 bytes) = 16 bytes total
    //  - R6 - NFT id of tracker server (bytes)
    //
    // Actions:
    //  - redeem note (#0)
    //  - top up      (#1)
    //
    //  Tracker box registers:
    //  - R4 - tracker's signing key (GroupElement)
    //  - R5 - AVL tree commitment to offchain credit data
    //         stores: hash(A_pubkey || B_pubkey) -> totalDebt
    //         This on-chain commitment allows the reserve contract to verify that the tracker
    //         is attesting to a debt amount that is actually recorded in its state.
    //         During redemption, context var #8 provides the AVL proof for looking up
    //         hash(ownerKey || receiverKey) in this tree to verify totalDebt.

    // Context extension variables for redemption:
    // #1 - receiver pubkey (as a GroupElement)
    // #2 - reserve owner's signature bytes (Schnorr signature on key || totalDebt || timestamp)
    // #3 - current total debt amount (Long)
    // #4 - timestamp of the payment (Long, milliseconds since Unix epoch)
    // #5 - proof for insertion into reserve's AVL tree (Coll[Byte])
    // #6 - tracker's signature bytes (Schnorr signature on key || totalDebt || timestamp)
    // #7 - [OPTIONAL] proof for AVL tree lookup in reserve's tree for hash(ownerKey||receiverKey) -> (timestamp, redeemedDebt)
    //      Not needed for first redemption (when redeemedDebt = 0)
    // #8 - proof for AVL tree lookup in tracker's tree for hash(ownerKey||receiverKey) -> totalDebt (required)

    // action and reserve output index. By passing them instead of hard-coding, we allow for multiple notes to be
    // redeemed at once, which can be used for atomic mutual debt clearing etc
    val v = getVar[Byte](0).get
    val action = v / 10
    val index = v % 10 // reserve output position

    val ownerKey = SELF.R4[GroupElement].get // reserve owner's key
    val selfOut = OUTPUTS(index)

    // Token IDs at positions #0 and #1 (reserve token is at #1)
    // These are used to verify token ID preservation in outputs
    val tokenId0 = SELF.tokens(0)._1
    val tokenId1 = SELF.tokens(1)._1

    // common checks for all the paths (not incl. token #1 value and R5 check)
    // Note: selfOut.tokens == SELF.tokens is NOT used here because token #1 amount changes
    val selfPreserved =
            selfOut.propositionBytes == SELF.propositionBytes &&
            selfOut.R4[GroupElement].get == SELF.R4[GroupElement].get &&
            selfOut.R6[Coll[Byte]].get == SELF.R6[Coll[Byte]].get

    // Verify token IDs at positions #0 and #1 are preserved in output
    // Token #0 value is always 1 (0-value tokens not possible in Ergo)
    // Token #1 value changes according to redemption/top-up logic
    val tokenIdsPreserved = 
        selfOut.tokens.size == 2 &&
        selfOut.tokens(0)._1 == tokenId0 &&
        selfOut.tokens(1)._1 == tokenId1

    if (action == 0) {
      // redemption path
      // context extension variables used:
      // #1 - receiver pubkey (as a GroupElement)
      // #2 - reserve owner's signature bytes for the debt record (Schnorr signature on key || totalDebt || timestamp)
      // #3 - current total debt amount (Long)
      // #4 - timestamp of the payment (Long, milliseconds since Unix epoch)
      // #5 - proof for insertion into reserve's AVL tree (Coll[Byte])
      // #6 - tracker's signature bytes (Schnorr signature on key || totalDebt || timestamp)
      // #7 - [OPTIONAL] proof for AVL tree lookup in reserve's tree for hash(ownerKey||receiverKey) -> (timestamp, redeemedDebt)
      //      Not needed for first redemption (when redeemedDebt = 0)
      // #8 - proof for AVL tree lookup in tracker's tree for hash(ownerKey||receiverKey) -> totalDebt (required)

      // Base point for elliptic curve operations
      val g: GroupElement = groupGenerator

      // Tracker box holds the debt information as key-value pairs: hash(A||B) -> totalDebt
      val tracker = CONTEXT.dataInputs(0) // Data input: tracker box containing debt records
      val trackerNftId = tracker.tokens(0)._1 // NFT token ID identifying the tracker
      val trackerTree = tracker.R5[AvlTree].get // AVL tree commitment to offchain credit data
      val trackerPubKey = tracker.R4[GroupElement].get // Tracker's public key for signature verification
      val expectedTrackerId = SELF.R6[Coll[Byte]].get // Expected tracker ID stored in reserve contract

      // Verify that tracker identity matches
      val trackerIdCorrect = trackerNftId == expectedTrackerId

      // Receiver of the redemption (creditor)
      val receiver = getVar[GroupElement](1).get
      val receiverBytes = receiver.getEncoded // Receiver's public key bytes

      val ownerKeyBytes = ownerKey.getEncoded // Reserve owner's public key (from R4 register) bytes

      // Create key for debt record: hash(ownerKey || receiverKey)
      // This key is used in both:
      // - tracker's AVL tree (R5) for totalDebt lookup
      // - reserve's AVL tree (R5) for (timestamp, cumulative redeemed amount) lookup
      val key = blake2b256(ownerKeyBytes ++ receiverBytes)

      // Total debt amount and timestamp from context variables
      val totalDebt = getVar[Long](3).get
      val timestamp = getVar[Long](4).get

      // Verify totalDebt is committed in tracker's AVL tree using context variable #8
      // This ensures the tracker is attesting to a debt amount that exists in its on-chain commitment
      val trackerLookupProof = getVar[Coll[Byte]](8).get
      val trackerDebtBytes = trackerTree.get(key, trackerLookupProof).get
      val trackerTotalDebt = byteArrayToLong(trackerDebtBytes)
      val trackerDebtCorrect = trackerTotalDebt == totalDebt

      // Reserve owner's signature for the debt record
      val reserveSigBytes = getVar[Coll[Byte]](2).get

      // Lookup previous redemption state (timestamp and redeemed amount)
      // Value format: timestamp (8 bytes) ++ redeemedAmount (8 bytes) = 16 bytes
      val lookupProofOpt = getVar[Coll[Byte]](7)
      val storedTimestamp = if (lookupProofOpt.isDefined) {
        val valueBytes = SELF.R5[AvlTree].get.get(key, lookupProofOpt.get).get
        byteArrayToLong(valueBytes.slice(0, 8))
      } else {
        0L
      }
      val redeemedDebt = if (lookupProofOpt.isDefined) {
        val valueBytes = SELF.R5[AvlTree].get.get(key, lookupProofOpt.get).get
        byteArrayToLong(valueBytes.slice(8, 16))
      } else {
        0L
      }

      // Verify that the new timestamp is greater than the stored timestamp
      // This prevents replay attacks with old notes
      val timestampCorrect = timestamp > storedTimestamp

      // Check if enough time has passed for emergency redemption (without tracker signature)
      // NOTE: All debts associated with this tracker (both new and old) become eligible
      // for emergency redemption simultaneously after 3 days from tracker creation
      val trackerUpdateTime = tracker.creationInfo._1
      val enoughTimeSpent = (HEIGHT - trackerUpdateTime) > 3 * 720 // 3 days passed

      // Message to verify signatures: key || total debt || timestamp
      // In case of emergency exit: key || total debt || timestamp || 0L
      val message = if (enoughTimeSpent) {
         key ++ longToByteArray(totalDebt) ++ longToByteArray(timestamp) ++ longToByteArray(0L)
      } else {
         key ++ longToByteArray(totalDebt) ++ longToByteArray(timestamp)
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

      // Calculate amount being redeemed (in tokens) and verify it doesn't exceed debt
      // Token #1 is the reserve token, same position in input and output (checked by selfPreserved)
      val tokenAmountIn = SELF.tokens(1)._2
      val tokenAmountOut = selfOut.tokens(1)._2
      val redeemed = tokenAmountIn - tokenAmountOut

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

      // Update the AVL tree with new timestamp and cumulative redeemed amount
      // Value format: timestamp (8 bytes) ++ newRedeemedAmount (8 bytes) = 16 bytes
      val newRedeemed = redeemedDebt + redeemed
      val treeValue = longToByteArray(timestamp) ++ longToByteArray(newRedeemed)
      val redeemedKeyVal = (key, treeValue)  // key -> (timestamp, redeemed debt value)
      val insertProof = getVar[Coll[Byte]](5).get // Merkle proof for tree insertion
      val nextTree: AvlTree = SELF.R5[AvlTree].get.insert(Coll(redeemedKeyVal), insertProof).get // todo: insertOrUpdate after appkit update
      // Verify tree was properly updated in output
      val properRedemptionTree = nextTree == selfOut.R5[AvlTree].get

      // Verify receiver's signature on transaction bytes
      val receiverCondition = proveDlog(receiver)

      // Combine all validation conditions
      sigmaProp(selfPreserved &&
                tokenIdsPreserved &&
                trackerIdCorrect &&
                trackerDebtCorrect &&
                timestampCorrect &&
                properRedemptionTree &&
                properReserveSignature &&
                properlyRedeemed &&
                receiverCondition)
    } else if (action == 1) {
      // top up
      // Token #1 is the reserve token, same position in input and output
      val tokenAmountIn = SELF.tokens(1)._2
      val tokenAmountOut = selfOut.tokens(1)._2

      // Note: Custom tokens have no decimals, so minimum top-up is 1 token unit
      // (unlike ERG which uses 1000000 nanoERG as minimum)
      sigmaProp(
        selfPreserved &&
        tokenIdsPreserved &&
        selfOut.R5[AvlTree].get == SELF.R5[AvlTree].get && // as R5 register preservation is not checked in selfPreserved
        (tokenAmountOut - tokenAmountIn >= 1) // at least 1 token unit added
      )
    } else {
      sigmaProp(false)
    }

}
