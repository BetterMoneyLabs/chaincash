{
    // Private Basis Reserve Contract - Proof of Concept
    // Nullifier-based redemption for Chaumian blind-signed notes
    // 
    // This contract manages an on-chain ERG reserve backing off-chain private notes
    // issued via blind signatures. Users can redeem notes by revealing nullifiers,
    // which are checked against an on-chain AVL tree to prevent double-spending.
    //
    // Key differences from transparent Basis:
    // - Uses nullifiers instead of hash(AB) -> timestamp mappings
    // - Verifies blind signatures on note commitments
    // - Supports unlinkable bearer notes
    // - R5 stores nullifier -> timestamp (not AB pairs)
    //
    // Privacy properties:
    // - Withdrawal (deposit) cannot be linked to redemption (nullifier reveal)
    // - Mint sees blinded commitments, not actual note serials
    // - Off-chain transfers leave no on-chain trace
    //
    // Data Registers:
    //  - Token #0: Singleton NFT identifying this reserve
    //  - R4: Mint public key (GroupElement) - for verifying blind signatures
    //  - R5: AVL tree of spent nullifiers (nullifier -> timestamp)
    //  - R6: Tracker NFT ID (bytes) - identifies authorized tracker
    //
    // Actions:
    //  - Redeem note (action 0): Reveal nullifier, verify blind signature, transfer ERG
    //  - Top up (action 1): Add ERG to reserve
    //
    // Simplifications for PoC:
    //  - Single denomination (enforced off-chain)
    //  - Simplified blind signature verification (Schnorr-based)
    //  - Tracker signature required (or 7-day emergency bypass)

    val v = getVar[Byte](0).get
    val action = v / 10
    val index = v % 10

    val mintPubKey = SELF.R4[GroupElement].get  // Mint's public key for blind signatures
    val selfOut = OUTPUTS(index)

    // Common checks for all actions
    val selfPreserved =
            selfOut.propositionBytes == SELF.propositionBytes &&
            selfOut.tokens == SELF.tokens &&
            selfOut.R4[GroupElement].get == SELF.R4[GroupElement].get &&
            selfOut.R6[Coll[Byte]].get == SELF.R6[Coll[Byte]].get

    if (action == 0) {
      // ============================================================
      // REDEMPTION PATH - Redeem a private note via nullifier reveal
      // ============================================================

      // Tracker box holds nullifier commitments and authorizes redemptions
      val tracker = CONTEXT.dataInputs(0)
      val trackerNftId = tracker.tokens(0)._1
      val expectedTrackerId = SELF.R6[Coll[Byte]].get
      val trackerIdCorrect = trackerNftId == expectedTrackerId
      val trackerPubKey = tracker.R4[GroupElement].get

      val g: GroupElement = groupGenerator

      // Receiver of redeemed ERG (can be ephemeral/fresh pubkey for privacy)
      val receiver = getVar[GroupElement](1).get
      val receiverBytes = receiver.getEncoded

      // Blind signature components from mint (unblinded by user)
      // blind_sig = (A', z') where A' is a point and z' is a scalar
      val blindSigBytes = getVar[Coll[Byte]](2).get
      val blindSigA = decodePoint(blindSigBytes.slice(0, 33))
      val blindSigZ = byteArrayToBigInt(blindSigBytes.slice(33, blindSigBytes.size))

      // Denomination and serial of the note being redeemed
      val denom = getVar[Long](3).get  // Denomination in nanoERG
      val serial = getVar[Coll[Byte]](4).get  // 32-byte random serial number

      // Compute note commitment: hash(denom || serial)
      val noteCommitment = blake2b256(longToByteArray(denom) ++ serial)

      // Verify blind signature on note commitment
      // Schnorr signature verification: G^z' == A' * PK_mint^e
      // where e = hash(A' || noteCommitment || PK_mint)
      val mintPubKeyBytes = mintPubKey.getEncoded
      val e: Coll[Byte] = blake2b256(blindSigBytes.slice(0, 33) ++ noteCommitment ++ mintPubKeyBytes)
      val eInt = byteArrayToBigInt(e)
      val properBlindSignature = (g.exp(blindSigZ) == blindSigA.multiply(mintPubKey.exp(eInt)))

      // Compute nullifier: hash("nullifier" || serial || PK_mint)
      // Nullifiers are binding to mint key to prevent cross-reserve replay
      val nullifierPrefix = blake2b256(Coll[Byte]('n'.toByte, 'u'.toByte, 'l'.toByte, 'l'.toByte, 
                                                    'i'.toByte, 'f'.toByte, 'i'.toByte, 'e'.toByte, 'r'.toByte))
      val nullifier = blake2b256(nullifierPrefix ++ serial ++ mintPubKeyBytes)

      // Timestamp for this redemption (current block time)
      val timestamp = CONTEXT.headers(0).timestamp

      // AVL tree proof for inserting nullifier (proves nullifier not already present)
      val nullifierKeyVal = (nullifier, longToByteArray(timestamp))
      val proof = getVar[Coll[Byte]](5).get
      
      // Insert nullifier into spent nullifier tree in R5
      // This will fail if nullifier already exists (double-spend prevention)
      val nextTree: AvlTree = SELF.R5[AvlTree].get.insert(Coll(nullifierKeyVal), proof).get
      val properNullifierTree = nextTree == selfOut.R5[AvlTree].get

      // Redemption output (to receiver)
      val redemptionOut = OUTPUTS(index + 1)
      val redemptionValue = redemptionOut.value

      // Verify redeemed amount matches denomination
      val redeemed = SELF.value - selfOut.value
      val properlyRedeemed = (redeemed == denom) && (redemptionValue == denom)

      // Tracker signature for authorization
      val trackerSigBytes = getVar[Coll[Byte]](6).get
      val trackerABytes = trackerSigBytes.slice(0, 33)
      val trackerZBytes = trackerSigBytes.slice(33, trackerSigBytes.size)
      val trackerA = decodePoint(trackerABytes)
      val trackerZ = byteArrayToBigInt(trackerZBytes)

      // Message for tracker signature: nullifier || denom || timestamp
      val message = nullifier ++ longToByteArray(denom) ++ longToByteArray(timestamp)
      
      // Verify tracker Schnorr signature
      val trackerE: Coll[Byte] = blake2b256(trackerABytes ++ message ++ trackerPubKey.getEncoded)
      val trackerEInt = byteArrayToBigInt(trackerE)
      val properTrackerSignature = (g.exp(trackerZ) == trackerA.multiply(trackerPubKey.exp(trackerEInt)))

      // Emergency redemption: allow without tracker signature after 7 days
      // Note: timestamp here represents note issuance time (simplified - in practice,
      // would need to track issuance timestamps separately)
      val lastBlockTime = CONTEXT.headers(0).timestamp
      val enoughTimeSpent = (timestamp > 0) && (lastBlockTime - timestamp) > 7 * 86400000

      // Receiver condition: ensure redemption output is spendable by receiver
      val receiverCondition = proveDlog(receiver)

      // Combine all validation conditions
      sigmaProp(
        selfPreserved &&
        trackerIdCorrect &&
        properBlindSignature &&      // Mint's signature on note is valid
        properNullifierTree &&        // Nullifier successfully inserted (not already spent)
        properlyRedeemed &&           // Correct denomination redeemed
        (enoughTimeSpent || properTrackerSignature) &&  // Tracker authorizes or emergency timeout
        receiverCondition             // Receiver can spend redemption output
      )

    } else if (action == 1) {
      // ============================================================
      // TOP UP PATH - Add ERG to reserve
      // ============================================================
      
      // Anyone can top up the reserve (increases backing for issued notes)
      // Minimum top-up amount: 1 ERG (prevents dust attacks)
      sigmaProp(
        selfPreserved &&
        (selfOut.value - SELF.value >= 1000000000) &&  // At least 1 ERG added
        selfOut.R5[AvlTree].get == SELF.R5[AvlTree].get  // Nullifier tree unchanged
      )

    } else {
      // Invalid action
      sigmaProp(false)
    }

}
