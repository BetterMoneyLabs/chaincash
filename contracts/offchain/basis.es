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
    //  * a tracker holds cumulative A -> B debt (as positive ever increasing number).
    //    A key->value dictionary is used to store the data as hash(A_pubkey || B_pubkey) -> totalDebt,
    //    where totalDebt is the cumulative amount of debt A owes to B.
    //  * to make a (new payment) to B, A is taking current AB record, increasing cumulative debt,
    //    signing the updated record (message: hash(A||B) || totalDebt) and sending it to the tracker.
    //  * tracker is periodically committing to its state (dictionary) by posting its digest on chain
    //    via an AVL tree in register R5. The tree stores hash(A||B) -> totalDebt mappings.
    //  * at any moment it is possible to redeem A's debt to B by calling redemption action of the reserve contract below.
    //    The contract tracks cumulative amount of debt already redeemed for each (owner, receiver) pair in an AVL tree.
    //    Redemption requires BOTH reserve owner's signature AND tracker's signature on message: hash(ownerKey||receiverKey) || totalDebt.
    //    The tracker signature guarantees that the offchain state is consistent and prevents double-spending.
    //    Additionally, the contract verifies that totalDebt is committed in the tracker's AVL tree (context var #8 provides lookup proof).
    //  * to redeem: B contacts tracker to obtain signature on the debt note, then presents reserve owner's signature
    //    (from original IOU note) and tracker's signature to the on-chain contract along with AVL tree proofs:
    //    - proof for reserve tree lookup (context var #7, optional for first redemption)
    //    - proof for tracker tree lookup (context var #8, required)
    //  * always possible to top up the reserve. To redeem partially, reserve holder can make an offchain payment to self (A -> A)
    //    updating the cumulative debt, then redeem the desired amount.
    //
    // Debt Transfer (Novation):
    //  * The scheme supports transferring debt obligations between creditors with debtor consent.
    //  * Example: A owes debt to B. B wants to buy from C. If A agrees, A's debt to B can be decreased
    //    and A's debt to C can be increased by the same amount.
    //  * Process:
    //    1. B initiates transfer: requests to transfer amount X from debt(A->B) to debt(A->C)
    //    2. A signs the transfer: message includes hash(A||B), hash(A||C), and transfer amount X
    //    3. Tracker verifies: debt(A->B) >= X, then updates both records atomically
    //    4. Tracker commits: posts updated AVL tree with decreased debt(A->B) and increased debt(A->C)
    //  * This allows debt to circulate in the network: A's credit with B can be used to pay C,
    //    effectively making debt notes transferrable with debtor consent.
    //  * Benefits:
    //    - Enables triangular trade: A->B->C becomes A->C (B is paid by debt transfer)
    //    - Reduces need for on-chain redemption: debt can be re-assigned offchain
    //    - Maintains security: debtor must consent, tracker must verify and commit

    // Security analysis and the role of the tracker:
    //  * the usual problem is that A can pay to B and then create a note from A to self and redeem. Solved by tracker solely.
    //  * double spending of a note is not possible by contract design (AVL tree tracks cumulative redeemed amounts).
    //  * tracker cannot steal funds as both owner and tracker signatures are required for redemption.
    //  * tracker can re-order redemption transactions, potentially affecting outcome for undercollateralized notes.
    //  * debt transfer security:
    //    - debtor (A) must sign: prevents unauthorized transfer of debt obligation
    //    - tracker verifies source debt exists: prevents creating debt(A->C) without sufficient debt(A->B)
    //    - atomic update: both decrease(A->B) and increase(A->C) happen together or not at all
    //    - tracker cannot forge transfer: requires A's signature on transfer message

    // Normal workflow:
    // * A is willing to buy some services from B. A asks B whether debt notes (IOU) are accepted as payment.
    //   This can be done non-interactively if B publishes their acceptance predicate.
    // * If A's debt note is acceptable, A creates an IOU note with cumulative debt amount and signs it
    //   (signature on message: hash(A_pubkey || B_pubkey) || totalDebt). A sends the note to the tracker.
    // * The tracker verifies the note against its state, updates its internal ledger, and provides a signature
    //   on the same message. This tracker signature is required for on-chain redemption.
    // * A sends both signatures (A's and tracker's) to B. B now holds a valid, redeemable IOU note.
    // * At any time, B can redeem the debt by presenting both signatures to the reserve contract along with
    //   an AVL tree proof showing the cumulative redeemed amount. The contract verifies both signatures and
    //   ensures the redeemed amount doesn't exceed (totalDebt - alreadyRedeemed).
    // * At any time, A can make another payment to B by signing a message with increased cumulative debt amount.
    // * A can refund by redeeming like B (in pseudonymous environments, A may have multiple keys).
    //   B should always track collateralization level and can prepare redemption transactions in advance.
    //
    // Debt Transfer Workflow (Triangular Trade):
    // * Scenario: A owes 10 ERG to B. B wants to buy 5 ERG worth of services from C.
    // * Step 1: B proposes to C that B will pay via debt transfer from A. C agrees.
    // * Step 2: B requests transfer from tracker: decrease debt(A->B) by 5 ERG, increase debt(A->C) by 5 ERG.
    // * Step 3: Tracker notifies A of the transfer request. A verifies the purchase (B->C) and signs approval.
    // * Step 4: A's signature message: hash(A||B) || hash(A||C) || 5000000000L (transfer amount)
    // * Step 5: Tracker verifies: debt(A->B) >= 5 ERG, A's signature is valid.
    // * Step 6: Tracker atomically updates: debt(A->B) -= 5 ERG, debt(A->C) += 5 ERG.
    // * Step 7: Tracker posts updated AVL tree commitment on-chain.
    // * Result: B is paid (debt reduced), C is creditor (new debt created), A owes C instead of B.
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

    // Examples and demos:

    // Example of AI agents self-sovereign economy:
    // * repo maintainer agent A is looking for new issues since last scan, picking one to work on,
    //   and choose agent candidates set with needed skills (frontend, backend, testing, etc)
    // * after having corresponding PR merged, A will have reward in git tokens, but it doesnt have it at this point.
    //   so it is reaching agents offering to accept a debt note
    // * let's assume some agent B is found and agreed to make a work on credit. He is sending work done to A ,
    //   A is checking it with another agent C (paying with debt note as well) and opening a PR after all
    // * when PR is merged, A is getting paid in git tokens, he may convert them into ERG in a liquidity pool, for example
    //   and create an on-chain reserve. B and C now can exchange promisory notes for ERG using the reserve smart contract
    //
    // Example with Debt Transfer (Triangular Trade):
    // * Agent A (repo maintainer) owes 10 ERG to Agent B (frontend dev) for completed work.
    // * Agent A needs testing work from Agent C (tester) but hasn't created reserve yet.
    // * Agent B needs testing work from Agent C (5 ERG worth).
    // * Instead of B paying C separately, they use debt transfer:
    //   - B requests: transfer 5 ERG from debt(A->B) to debt(A->C)
    //   - A verifies B's work was satisfactory and approves the transfer
    //   - Tracker updates: debt(A->B) = 5 ERG, debt(A->C) = 5 ERG
    // * Result: B effectively paid C using A's debt obligation. A now owes C directly.
    // * When A creates reserve, both B and C can redeem their respective portions.
    // * This creates a chain of trust: A's creditworthiness backs payments to B and C.

    // Example of digital trading in occasionaly connected area:
    // * imagine some area which is mostly disconnected from the internet but having connection occasionally
    // * but it has a local tracker
    // * so traders in the area can trade still, creating credit. When credit limits are exceeded (i.e. no more trust
    //   could be given), on-chain reserves can be used, with redemption transactions to be collected by the tracker.
    //   Once there is even super-slow Internet connection, tracker can send them with getting lean confirmations
    //   via NiPoPoWs ( similarly to https://www.ergoforum.org/t/e-mail-client-for-limited-or-blocked-internet/134 )

    // Possible extensions:

    // Data:
    //  - R4 - signing key (as a group element)
    //  - R5 - AVL tree tracking cumulative redeemed debt per (owner, receiver) pair
    //         stores: hash(ownerKey || receiverKey) -> cumulativeRedeemedAmount
    //  - R6 - NFT id of tracker server (bytes) // todo: support multiple payment servers by using a tree
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
      // #1 - receiver pubkey (as a GroupElement)
      // #2 - reserve owner's signature bytes for the debt record (Schnorr signature on key || totalDebt)
      // #3 - current total debt amount (Long)
      // #5 - proof for insertion into reserve's AVL tree (Coll[Byte])
      // #6 - tracker's signature bytes (Schnorr signature on key || totalDebt or key || totalDebt || 0L for emergency)
      // #7 - [OPTIONAL] proof for AVL tree lookup in reserve's tree for hash(ownerKey||receiverKey) -> redeemedDebt
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
      // - reserve's AVL tree (R5) for cumulative redeemed amount lookup
      val key = blake2b256(ownerKeyBytes ++ receiverBytes)

      // Total debt amount from context variables
      val totalDebt = getVar[Long](3).get

      // Verify totalDebt is committed in tracker's AVL tree using context variable #8
      // This ensures the tracker is attesting to a debt amount that exists in its on-chain commitment
      val trackerLookupProof = getVar[Coll[Byte]](8).get
      val trackerDebtBytes = trackerTree.get(key, trackerLookupProof).get
      val trackerTotalDebt = byteArrayToLong(trackerDebtBytes)
      val trackerDebtCorrect = trackerTotalDebt == totalDebt

      // Reserve owner's signature for the debt record
      val reserveSigBytes = getVar[Coll[Byte]](2).get

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
                trackerDebtCorrect &&
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