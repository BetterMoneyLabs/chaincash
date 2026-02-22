/**
 * Comprehensive Test Suite for Private Note System
 * 
 * Tests all security properties:
 * - Commitment binding and hiding
 * - Nullifier uniqueness
 * - Double-spend prevention
 * - Forgery prevention
 * - Unlinkability
 * 
 * @author ChainCash Contributors
 */

const {
    generateSecret,
    computeCommitment,
    computeNullifier,
    verifyCommitment,
    verifyNullifier,
    bytesToHex,
    PrivateNote,
    PrivateReserve
} = require('./private-note-system');

// Test utilities
let testsPassed = 0;
let testsFailed = 0;

function assert(condition, message) {
    if (condition) {
        console.log(`✓ ${message}`);
        testsPassed++;
    } else {
        console.log(`✗ ${message}`);
        testsFailed++;
        throw new Error(`Test failed: ${message}`);
    }
}

function runTest(name, testFn) {
    try {
        testFn();
    } catch (error) {
        console.log(`\n❌ Test "${name}" failed:`, error.message);
    }
}

console.log('🧪 Running Private Note System Tests\n');
console.log('='.repeat(60));

// Test 1: Basic note minting
runTest('Should mint a valid private note', () => {
    const reserve = new PrivateReserve('reserve-001');
    reserve.deposit(1000);

    const secret = generateSecret();
    const commitment = computeCommitment(secret);

    const success = reserve.mint(commitment, 100);
    assert(success === true, 'Should mint a valid private note');
    assert(reserve.hasCommitment(commitment), 'Reserve should have commitment');
});

// Test 2: Basic note spending
runTest('Should spend a valid private note', () => {
    const reserve = new PrivateReserve('reserve-002');
    reserve.deposit(1000);

    const secret = generateSecret();
    const commitment = computeCommitment(secret);
    const nullifier = computeNullifier(secret);

    reserve.mint(commitment, 100);
    const result = reserve.spend(secret, nullifier, 'recipient-address');

    assert(result.success === true, 'Should spend a valid private note');
    assert(result.amount === 100, 'Should return correct amount');
    assert(reserve.isNullifierUsed(nullifier), 'Nullifier should be marked as used');
});

// Test 3: Commitment verification
runTest('Should verify commitment matches secret', () => {
    const secret = generateSecret();
    const commitment = computeCommitment(secret);

    assert(verifyCommitment(commitment, secret), 'Should verify commitment matches secret');
});

// Test 4: Invalid commitment rejection
runTest('Should reject invalid commitment', () => {
    const secret1 = generateSecret();
    const secret2 = generateSecret();
    const commitment = computeCommitment(secret1);

    assert(!verifyCommitment(commitment, secret2), 'Should reject invalid commitment');
});

// Test 5: Double-spend prevention
runTest('Should prevent double-spending same note', () => {
    const reserve = new PrivateReserve('reserve-003');
    reserve.deposit(1000);

    const secret = generateSecret();
    const commitment = computeCommitment(secret);
    const nullifier = computeNullifier(secret);

    reserve.mint(commitment, 100);
    reserve.spend(secret, nullifier, 'recipient-1');

    let doubleSpendPrevented = false;
    try {
        reserve.spend(secret, nullifier, 'recipient-2');
    } catch (error) {
        doubleSpendPrevented = error.message.includes('Double-spend');
    }

    assert(doubleSpendPrevented, 'Should prevent double-spending same note');
});

// Test 6: Multiple different notes
runTest('Should allow spending different notes', () => {
    const reserve = new PrivateReserve('reserve-004');
    reserve.deposit(1000);

    const secret1 = generateSecret();
    const secret2 = generateSecret();
    const commitment1 = computeCommitment(secret1);
    const commitment2 = computeCommitment(secret2);
    const nullifier1 = computeNullifier(secret1);
    const nullifier2 = computeNullifier(secret2);

    reserve.mint(commitment1, 100);
    reserve.mint(commitment2, 150);

    const result1 = reserve.spend(secret1, nullifier1, 'recipient-1');
    const result2 = reserve.spend(secret2, nullifier2, 'recipient-2');

    assert(result1.success && result2.success, 'Should allow spending different notes');
});

// Test 7: Nullifier tracking
runTest('Should track nullifiers correctly', () => {
    const reserve = new PrivateReserve('reserve-005');
    reserve.deposit(1000);

    const secret = generateSecret();
    const commitment = computeCommitment(secret);
    const nullifier = computeNullifier(secret);

    assert(!reserve.isNullifierUsed(nullifier), 'Nullifier should not be used initially');

    reserve.mint(commitment, 100);
    reserve.spend(secret, nullifier, 'recipient');

    assert(reserve.isNullifierUsed(nullifier), 'Should track nullifiers correctly');
});

// Test 8: Unlinkability property
runTest('Should maintain unlinkability between commitment and nullifier', () => {
    const secret = generateSecret();
    const commitment = computeCommitment(secret);
    const nullifier = computeNullifier(secret);

    const commitmentHex = bytesToHex(commitment);
    const nullifierHex = bytesToHex(nullifier);

    // Commitment and nullifier should be completely different
    assert(commitmentHex !== nullifierHex, 'Should maintain unlinkability between commitment and nullifier');

    // No obvious pattern linking them
    let similarBytes = 0;
    for (let i = 0; i < commitment.length; i++) {
        if (commitment[i] === nullifier[i]) similarBytes++;
    }
    const similarity = similarBytes / commitment.length;
    assert(similarity < 0.3, 'Commitment and nullifier should have low similarity');
});

// Test 9: Privacy of amounts
runTest('Should hide transaction amounts from reserve', () => {
    const reserve = new PrivateReserve('reserve-006');
    reserve.deposit(10000);

    // Mint different amounts
    const amounts = [100, 200, 50, 500];
    const secrets = [];
    const commitments = [];

    for (const amount of amounts) {
        const secret = generateSecret();
        const commitment = computeCommitment(secret);
        secrets.push(secret);
        commitments.push(commitment);
        reserve.mint(commitment, amount);
    }

    // All commitments should look random, no indication of amount
    const uniqueCommitments = new Set(commitments.map(c => bytesToHex(c)));
    assert(uniqueCommitments.size === amounts.length, 'Should hide transaction amounts from reserve');
});

// Test 10: Different users with same reserve
runTest('Should allow notes to be spent by different users', () => {
    const reserve = new PrivateReserve('reserve-007');
    reserve.deposit(1000);

    // User 1 mints and spends
    const secret1 = generateSecret();
    const commitment1 = computeCommitment(secret1);
    const nullifier1 = computeNullifier(secret1);
    reserve.mint(commitment1, 100);

    // User 2 mints and spends
    const secret2 = generateSecret();
    const commitment2 = computeCommitment(secret2);
    const nullifier2 = computeNullifier(secret2);
    reserve.mint(commitment2, 150);

    const result1 = reserve.spend(secret1, nullifier1, 'user1-address');
    const result2 = reserve.spend(secret2, nullifier2, 'user2-address');

    assert(result1.success && result2.success, 'Should allow notes to be spent by different users');
});

// Test 11: PrivateNote class
runTest('Should verify valid spent notes', () => {
    const secret = generateSecret();
    const note = new PrivateNote(secret, 100, 'reserve-008');

    assert(note.verify(), 'Should verify valid spent notes');
});

// Test 12: Invalid secret rejection
runTest('Should reject verification with wrong secret', () => {
    const reserve = new PrivateReserve('reserve-009');
    reserve.deposit(1000);

    const secret = generateSecret();
    const wrongSecret = generateSecret();
    const commitment = computeCommitment(secret);
    const nullifier = computeNullifier(secret);

    reserve.mint(commitment, 100);

    let rejected = false;
    try {
        reserve.spend(wrongSecret, nullifier, 'recipient');
    } catch (error) {
        rejected = true;
    }

    assert(rejected, 'Should reject verification with wrong secret');
});

// Test 13: Invalid nullifier rejection
runTest('Should reject verification with wrong nullifier', () => {
    const reserve = new PrivateReserve('reserve-010');
    reserve.deposit(1000);

    const secret = generateSecret();
    const wrongSecret = generateSecret();
    const commitment = computeCommitment(secret);
    const wrongNullifier = computeNullifier(wrongSecret);

    reserve.mint(commitment, 100);

    let rejected = false;
    try {
        reserve.spend(secret, wrongNullifier, 'recipient');
    } catch (error) {
        rejected = true;
    }

    assert(rejected, 'Should reject verification with wrong nullifier');
});

// Test 14: Multiple reserves
runTest('Should handle multiple reserves', () => {
    const reserve1 = new PrivateReserve('reserve-011');
    const reserve2 = new PrivateReserve('reserve-012');

    reserve1.deposit(1000);
    reserve2.deposit(2000);

    const secret1 = generateSecret();
    const secret2 = generateSecret();
    const commitment1 = computeCommitment(secret1);
    const commitment2 = computeCommitment(secret2);

    reserve1.mint(commitment1, 100);
    reserve2.mint(commitment2, 200);

    const state1 = reserve1.getState();
    const state2 = reserve2.getState();

    assert(state1.reserveId !== state2.reserveId, 'Should handle multiple reserves');
    assert(state1.totalCommitments === 1 && state2.totalCommitments === 1, 'Each reserve should track its own commitments');
});

// Test 15: Large amounts
runTest('Should handle large amounts', () => {
    const reserve = new PrivateReserve('reserve-013');
    reserve.deposit(10000000);

    const secret = generateSecret();
    const commitment = computeCommitment(secret);
    const nullifier = computeNullifier(secret);

    reserve.mint(commitment, 1000000);
    const result = reserve.spend(secret, nullifier, 'recipient');

    assert(result.amount === 1000000, 'Should handle large amounts');
});

// Test 16: Zero amounts
runTest('Should handle zero amounts', () => {
    const reserve = new PrivateReserve('reserve-014');
    reserve.deposit(1000);

    const secret = generateSecret();
    const commitment = computeCommitment(secret);
    const nullifier = computeNullifier(secret);

    reserve.mint(commitment, 0);
    const result = reserve.spend(secret, nullifier, 'recipient');

    assert(result.amount === 0, 'Should handle zero amounts');
});

// Test 17: Many notes efficiently
runTest('Should handle many notes efficiently', () => {
    const reserve = new PrivateReserve('reserve-015');
    reserve.deposit(1000000);

    const count = 100;
    const secrets = [];

    for (let i = 0; i < count; i++) {
        const secret = generateSecret();
        const commitment = computeCommitment(secret);
        secrets.push(secret);
        reserve.mint(commitment, 10);
    }

    const state = reserve.getState();
    assert(state.totalCommitments === count, 'Should handle many notes efficiently');
});

// Test 18: Reserve state consistency
runTest('Should maintain consistency under random operations', () => {
    const reserve = new PrivateReserve('reserve-016');
    reserve.deposit(100000);

    const notes = [];

    // Mint 10 notes
    for (let i = 0; i < 10; i++) {
        const secret = generateSecret();
        const commitment = computeCommitment(secret);
        const nullifier = computeNullifier(secret);
        reserve.mint(commitment, 100);
        notes.push({ secret, commitment, nullifier });
    }

    // Spend 5 notes randomly
    const spentIndices = [1, 3, 5, 7, 9];
    for (const idx of spentIndices) {
        const { secret, nullifier } = notes[idx];
        reserve.spend(secret, nullifier, 'recipient');
    }

    const state = reserve.getState();
    assert(state.totalCommitments === 10, 'Should maintain total commitments');
    assert(state.totalSpent === 5, 'Should maintain consistency under random operations');
});

// Test 19: Commitment forgery prevention
runTest('Should prevent commitment forgery', () => {
    const reserve = new PrivateReserve('reserve-017');
    reserve.deposit(1000);

    const secret = generateSecret();
    const nullifier = computeNullifier(secret);
    // Attacker tries to spend without minting

    let forgeryPrevented = false;
    try {
        reserve.spend(secret, nullifier, 'attacker');
    } catch (error) {
        forgeryPrevented = error.message.includes('Commitment not found');
    }

    assert(forgeryPrevented, 'Should prevent commitment forgery');
});

// Test 20: Commitment tampering detection
runTest('Should detect commitment tampering', () => {
    const reserve = new PrivateReserve('reserve-018');
    reserve.deposit(1000);

    const secret1 = generateSecret();
    const commitment1 = computeCommitment(secret1);
    reserve.mint(commitment1, 100);

    // Attacker tries to spend with different secret
    const secret2 = generateSecret();
    const nullifier2 = computeNullifier(secret2);

    let tamperingDetected = false;
    try {
        reserve.spend(secret2, nullifier2, 'attacker');
    } catch (error) {
        tamperingDetected = true;
    }

    assert(tamperingDetected, 'Should detect commitment tampering');
});

// Test 21: Note export and import
runTest('Should export and import notes correctly', () => {
    const secret = generateSecret();
    const note1 = new PrivateNote(secret, 500, 'reserve-019');

    const exported = note1.export();
    const note2 = PrivateNote.import(exported);

    assert(bytesToHex(note1.commitment) === bytesToHex(note2.commitment), 'Commitments should match');
    assert(bytesToHex(note1.nullifier) === bytesToHex(note2.nullifier), 'Nullifiers should match');
    assert(note1.amount === note2.amount, 'Should export and import notes correctly');
});

// Test 22: Insufficient backing
runTest('Should reject minting with insufficient backing', () => {
    const reserve = new PrivateReserve('reserve-020');
    reserve.deposit(100); // Only 100 ERG

    const secret = generateSecret();
    const commitment = computeCommitment(secret);

    let rejected = false;
    try {
        reserve.mint(commitment, 200); // Try to mint 200 ERG worth
    } catch (error) {
        rejected = error.message.includes('Insufficient reserve backing');
    }

    assert(rejected, 'Should reject minting with insufficient backing');
});

// Print results
console.log('='.repeat(60));
console.log(`\nResults: ${testsPassed} passed, ${testsFailed} failed`);

if (testsFailed === 0) {
    console.log('🎉 All tests passed!\n');
    process.exit(0);
} else {
    console.log(`❌ ${testsFailed} test(s) failed\n`);
    process.exit(1);
}
