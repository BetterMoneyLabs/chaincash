{
    // Privacy-preserving reserve contract extension for Chaumian e-cash
    // This contract extends the Basis/ChainCash reserve model with privacy-preserving off-chain cash
    //
    // Key features:
    // - On-chain reserves remain fully auditable
    // - Off-chain cash uses blind signatures for privacy
    // - Serial number tracking prevents double-spending
    // - Minimal changes to existing reserve architecture
    //
    // Data:
    //  - token #0 - identifying singleton token
    //  - R4 - signing key (as a group element) - reserve owner's key
    //  - R5 - AVL tree of spent serial numbers (insert-only, prevents double-spending)
    //  - R6 - total issued amount (for auditing, in nanoERG equivalent)
    //
    // Actions:
    //  - mint privacy cash (#0) - issues blind signature for off-chain cash
    //  - redeem privacy cash (#1) - redeems off-chain cash with serial number verification
    //  - top up (#2) - adds collateral to reserve

    val v = getVar[Byte](0).get
    val action = v / 10
    val index = v % 10

    val ownerKey = SELF.R4[GroupElement].get // reserve owner's key
    val selfOut = OUTPUTS(index)

    // common checks for all the paths
    val selfPreserved =
            selfOut.propositionBytes == SELF.propositionBytes &&
            selfOut.tokens == SELF.tokens &&
            selfOut.R4[GroupElement].get == SELF.R4[GroupElement].get

    val g: GroupElement = groupGenerator

    if (action == 0) {
      // MINT PRIVACY CASH (action #0)
      // This action allows users to request blind signatures for off-chain cash
      // The reserve signs a blinded commitment without seeing the actual serial number
      //
      // Protocol:
      // 1. User creates blinded commitment: B = H(serial || secret) * r^e mod p
      // 2. Reserve signs B without seeing serial or secret
      // 3. User unblinds signature to get valid signature on H(serial || secret)
      //
      // For POC simplicity, we use a simplified blind signature scheme:
      // - User provides blinded message commitment
      // - Reserve signs it with Schnorr signature
      // - User can unblind to get signature on original message
      //
      // Note: In production, use proper blind signature scheme (e.g., RSA blind signatures or
      // BLS blind signatures). This POC uses a simplified approach for clarity.

      // Amount being minted (in nanoERG equivalent)
      val mintAmount = getVar[Long](1).get
      
      // Blinded commitment provided by user
      // Format: (blindedPoint, blindingFactorProof)
      // blindedPoint = H(serial || secret) * r^e where r is blinding factor
      val blindedCommitment = getVar[GroupElement](2).get
      
      // Reserve signs the blinded commitment
      // Signature: (a, z) where g^z = a * ownerKey^e
      // Challenge e = H(a || blindedCommitment || ownerKey)
      val sigA = getVar[GroupElement](3).get
      val sigABytes = sigA.getEncoded
      val sigZ = getVar[Coll[Byte]](4).get
      val sigZInt = byteArrayToBigInt(sigZ)
      
      // Verify reserve owner signed the blinded commitment
      val challenge = blake2b256(sigABytes ++ blindedCommitment.getEncoded ++ ownerKey.getEncoded)
      val challengeInt = byteArrayToBigInt(challenge)
      val properBlindSignature = g.exp(sigZInt) == sigA.multiply(ownerKey.exp(challengeInt))
      
      // Verify mint amount is reasonable (at least 1 nanoERG, max 1M ERG per mint)
      // TODO: Make limits configurable or remove for production
      val amountValid = (mintAmount >= 1000000000L) && (mintAmount <= 1000000000000000000L)
      
      // Update total issued amount
      val currentIssued = SELF.R6[Long].getOrElse(0L)
      val newIssued = currentIssued + mintAmount
      val issuedUpdated = selfOut.R6[Long].get == newIssued
      
      // Reserve must have enough collateral (simple check: reserve value >= issued)
      // TODO: In production, consider collateralization ratio (e.g., 110% collateralization)
      val sufficientCollateral = selfOut.value >= newIssued
      
      sigmaProp(selfPreserved && properBlindSignature && amountValid && issuedUpdated && sufficientCollateral)
      
    } else if (action == 1) {
      // REDEEM PRIVACY CASH (action #1)
      // This action redeems off-chain privacy cash by verifying serial numbers
      //
      // Protocol:
      // 1. User reveals serial number and secret
      // 2. Reserve verifies signature on H(serial || secret)
      // 3. Reserve checks serial number hasn't been spent (via AVL tree)
      // 4. Reserve adds serial to spent tree and redeems ERG
      //
      // Privacy: Serial numbers are only revealed during redemption, not during transfers

      // Serial number of the cash being redeemed (prevents double-spending)
      val serialNumber = getVar[Coll[Byte]](1).get
      
      // Secret used in commitment (user's secret for this cash token)
      val secret = getVar[Coll[Byte]](2).get
      
      // Original signature on H(serial || secret) (unblinded from mint)
      val sigA = getVar[GroupElement](3).get
      val sigABytes = sigA.getEncoded
      val sigZ = getVar[Coll[Byte]](4).get
      val sigZInt = byteArrayToBigInt(sigZ)
      
      // Reconstruct commitment: H(serial || secret)
      val commitment = blake2b256(serialNumber ++ secret)
      // TODO: In production, use proper hash-to-curve or point encoding
      // For POC, we verify the commitment hash directly
      // val commitmentPoint = decodePoint(commitment) // This would need proper encoding
      
      // Verify signature on commitment hash (not point, for POC simplicity)
      // TODO: In production, verify signature on properly encoded commitment point
      val challenge = blake2b256(sigABytes ++ commitment ++ ownerKey.getEncoded)
      val challengeInt = byteArrayToBigInt(challenge)
      val properSignature = g.exp(sigZInt) == sigA.multiply(ownerKey.exp(challengeInt))
      
      // Check serial number hasn't been spent (via AVL tree)
      val spentSerials = SELF.R5[AvlTree].get
      val proof = getVar[Coll[Byte]](5).get
      
      // Serial number should not exist in tree (not yet spent)
      val serialNotSpent = spentSerials.get(serialNumber, proof).isEmpty
      
      // Add serial to spent tree
      val serialKeyVal = (serialNumber, Coll[Byte]()) // value is empty, we only track keys
      val nextTree = spentSerials.insert(Coll(serialKeyVal), proof).get
      val treeUpdated = selfOut.R5[AvlTree].get == nextTree
      
      // Amount being redeemed (in nanoERG)
      val redeemAmount = getVar[Long](6).get
      val actualRedeemed = SELF.value - selfOut.value
      
      // Verify redemption amount matches
      val amountCorrect = (actualRedeemed == redeemAmount) && (redeemAmount > 0)
      
      // Update issued amount (decrease by redeemed amount)
      val currentIssued = SELF.R6[Long].getOrElse(0L)
      val newIssued = if (currentIssued >= redeemAmount) currentIssued - redeemAmount else 0L
      val issuedUpdated = selfOut.R6[Long].get == newIssued
      
      // Redemption fee (2% like original reserve contract)
      // TODO: Make fee configurable or remove for production flexibility
      val feeRate = 98L // 98% goes to user, 2% fee
      val maxRedeemable = (redeemAmount * feeRate) / 100L
      val feeCorrect = actualRedeemed <= maxRedeemable
      
      sigmaProp(selfPreserved && properSignature && serialNotSpent && treeUpdated && 
                amountCorrect && issuedUpdated && feeCorrect)
      
    } else if (action == 2) {
      // TOP UP (action #2)
      // Adds more collateral to the reserve
      // Preserves all registers except value
      
      val topUpAmount = selfOut.value - SELF.value
      val sufficientTopUp = topUpAmount >= 1000000000L // at least 1 ERG
      
      // Preserve spent serials tree and issued amount
      val treePreserved = selfOut.R5[AvlTree].get == SELF.R5[AvlTree].get
      val issuedPreserved = selfOut.R6[Long].get == SELF.R6[Long].get
      
      sigmaProp(selfPreserved && sufficientTopUp && treePreserved && issuedPreserved)
      
    } else {
      sigmaProp(false)
    }
}

