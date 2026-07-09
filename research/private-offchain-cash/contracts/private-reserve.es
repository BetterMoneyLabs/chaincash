{
    // Private Reserve Contract for ChainCash
    // Implements commitment-nullifier scheme for private notes
    
    // Data Registers:
    // - Token #0: Reserve identifier (singleton NFT)
    // - R4: Reserve owner's signing key (GroupElement)
    // - R5: Commitment tree (AvlTree) - Maps commitment -> amount
    // - R6: Nullifier set (AvlTree) - Set of spent nullifiers
    // - R7: Total ERG committed (Long)
    
    // Actions:
    // - Action 0: Deposit ERG backing
    // - Action 1: Mint private note (store commitment)
    // - Action 2: Spend private note (verify secret, check nullifier)
    
    val action = getVar[Byte](0).get
    val ownerKey = SELF.R4[GroupElement].get
    val commitmentTree = SELF.R5[AvlTree].get
    val nullifierTree = SELF.R6[AvlTree].get
    val totalCommitted = SELF.R7[Long].get
    
    val selfOut = OUTPUTS(0)
    
    // Common preservation checks
    val selfPreserved = 
        selfOut.propositionBytes == SELF.propositionBytes &&
        selfOut.tokens == SELF.tokens &&
        selfOut.R4[GroupElement].get == ownerKey
    
    if (action == 0) {
        // ============================================================
        // ACTION 0: DEPOSIT ERG BACKING
        // ============================================================
        // Anyone can deposit ERG to increase reserve backing
        // Preserves all state, increases value
        
        val depositAmount = selfOut.value - SELF.value
        val minDeposit = 1000000L // Minimum 0.001 ERG
        
        sigmaProp(
            selfPreserved &&
            selfOut.R5[AvlTree].get == commitmentTree &&
            selfOut.R6[AvlTree].get == nullifierTree &&
            selfOut.R7[Long].get == totalCommitted &&
            depositAmount >= minDeposit
        )
        
    } else if (action == 1) {
        // ============================================================
        // ACTION 1: MINT PRIVATE NOTE
        // ============================================================
        // User provides commitment = Blake2b(secret)
        // Reserve stores commitment and amount
        // Verifies sufficient backing exists
        
        val commitment = getVar[Coll[Byte]](1).get
        val amount = getVar[Long](2).get
        val commitmentProof = getVar[Coll[Byte]](3).get
        
        // Verify commitment is 32 bytes (Blake2b output)
        val validCommitmentSize = commitment.size == 32
        
        // Verify commitment doesn't already exist (prevent duplicate)
        val commitmentNotExists = !commitmentTree.contains(commitment, commitmentProof)
        
        // Verify sufficient backing
        val newTotalCommitted = totalCommitted + amount
        val sufficientBacking = SELF.value >= newTotalCommitted
        
        // Insert commitment into tree
        val amountBytes = longToByteArray(amount)
        val insertResult = commitmentTree.insert(Coll((commitment, amountBytes)), commitmentProof)
        val newCommitmentTree = insertResult.get
        
        // Output must have updated commitment tree and total
        val commitmentTreeUpdated = selfOut.R5[AvlTree].get.digest == newCommitmentTree.digest
        val totalUpdated = selfOut.R7[Long].get == newTotalCommitted
        
        // Nullifier tree unchanged
        val nullifierTreePreserved = selfOut.R6[AvlTree].get == nullifierTree
        
        // Value preserved (no ERG leaves reserve)
        val valuePreserved = selfOut.value >= SELF.value
        
        sigmaProp(
            selfPreserved &&
            validCommitmentSize &&
            commitmentNotExists &&
            sufficientBacking &&
            commitmentTreeUpdated &&
            totalUpdated &&
            nullifierTreePreserved &&
            valuePreserved
        )
        
    } else if (action == 2) {
        // ============================================================
        // ACTION 2: SPEND PRIVATE NOTE
        // ============================================================
        // User reveals secret and nullifier
        // Reserve verifies:
        //   1. commitment = Blake2b(secret) exists
        //   2. nullifier = Blake2b(secret || "nullifier") is valid
        //   3. nullifier not already used (double-spend check)
        // Then transfers amount to recipient
        
        val secret = getVar[Coll[Byte]](1).get
        val nullifier = getVar[Coll[Byte]](2).get
        val commitmentProof = getVar[Coll[Byte]](3).get
        val nullifierProof = getVar[Coll[Byte]](4).get
        val recipientIndex = getVar[Int](5).get
        
        // Verify inputs are correct size
        val validSecretSize = secret.size == 32
        val validNullifierSize = nullifier.size == 32
        
        // 1. Verify commitment = Blake2b(secret)
        val commitment = blake2b256(secret)
        
        // 2. Verify commitment exists in tree
        val commitmentLookup = commitmentTree.get(commitment, commitmentProof)
        val commitmentExists = commitmentLookup.isDefined
        val amountBytes = commitmentLookup.get
        val amount = byteArrayToLong(amountBytes)
        
        // 3. Verify nullifier = Blake2b(secret || "nullifier")
        val nullifierTag = "nullifier".getBytes
        val secretWithTag = secret ++ nullifierTag
        val expectedNullifier = blake2b256(secretWithTag)
        val validNullifier = nullifier == expectedNullifier
        
        // 4. Verify nullifier not already used (double-spend check)
        val nullifierNotUsed = !nullifierTree.contains(nullifier, nullifierProof)
        
        // 5. Insert nullifier into spent set
        val emptyValue = Coll[Byte]() // Nullifier set only needs keys, not values
        val nullifierInsertResult = nullifierTree.insert(Coll((nullifier, emptyValue)), nullifierProof)
        val newNullifierTree = nullifierInsertResult.get
        
        // 6. Update output state
        val nullifierTreeUpdated = selfOut.R6[AvlTree].get.digest == newNullifierTree.digest
        val commitmentTreePreserved = selfOut.R5[AvlTree].get == commitmentTree
        val totalPreserved = selfOut.R7[Long].get == totalCommitted
        
        // 7. Verify ERG transferred to recipient
        val recipientOut = OUTPUTS(recipientIndex)
        val ergTransferred = SELF.value - selfOut.value >= amount
        val recipientReceived = recipientOut.value >= amount
        
        // 8. Prevent value drainage (output value should be input - amount)
        val correctValueChange = selfOut.value >= SELF.value - amount
        
        sigmaProp(
            selfPreserved &&
            validSecretSize &&
            validNullifierSize &&
            commitmentExists &&
            validNullifier &&
            nullifierNotUsed &&
            nullifierTreeUpdated &&
            commitmentTreePreserved &&
            totalPreserved &&
            ergTransferred &&
            recipientReceived &&
            correctValueChange
        )
        
    } else if (action == 3) {
        // ============================================================
        // ACTION 3: OWNER WITHDRAWAL
        // ============================================================
        // Reserve owner can withdraw UNBACKED ERG only
        // Cannot withdraw ERG that backs committed notes
        
        val withdrawAmount = SELF.value - selfOut.value
        val unbacked = SELF.value - totalCommitted
        val validWithdrawal = withdrawAmount <= unbacked
        
        val statePreserved = 
            selfOut.R5[AvlTree].get == commitmentTree &&
            selfOut.R6[AvlTree].get == nullifierTree &&
            selfOut.R7[Long].get == totalCommitted
        
        proveDlog(ownerKey) && sigmaProp(
            selfPreserved &&
            statePreserved &&
            validWithdrawal &&
            selfOut.value >= totalCommitted // Always maintain backing
        )
        
    } else {
        // Invalid action
        sigmaProp(false)
    }
}
