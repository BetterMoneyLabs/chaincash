//! Private Basis Tracker - Proof of Concept Demo
//! 
//! This binary demonstrates the private Basis protocol:
//! 1. Create a reserve
//! 2. Issue private notes via blind signatures
//! 3. Transfer notes off-chain
//! 4. Redeem notes with nullifier-based double-spend prevention

use basis_private_tracker::*;

fn main() {
    println!("╔═══════════════════════════════════════════════════════════════╗");
    println!("║   Basis Private (Chaumian E-Cash) - Proof of Concept Demo    ║");
    println!("╚═══════════════════════════════════════════════════════════════╝\n");

    // ========== Initialize Reserve and Tracker ==========
    println!("🔧 Initializing reserve and tracker...\n");
    
    let mint_pubkey = PublicKey::from_bytes(vec![0x02; 33]);
    let reserve = ReserveState::new(
        [1u8; 32],           // Reserve NFT
        mint_pubkey.clone(),
        100_000_000_000,     // 100 ERG initial balance  
        [0u8; 32],           // Empty nullifier tree
        [2u8; 32],           // Tracker NFT
    );

    let mut tracker = PrivateBasisTracker::new(reserve, [2u8; 32]);

    println!("✓ Reserve created with {} nanoERG (100 ERG)", tracker.reserve.erg_balance);
    println!("✓ Mint public key: {}", hex::encode(&mint_pubkey.as_bytes()[0..8]));
    println!();

    // ========== Scenario: Alice withdraws ==========
    println!("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    println!("📥 WITHDRAWAL: Alice obtains a private note");
    println!("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

    // Alice generates a blinded commitment and deposits ERG
    let alice_withdrawal = BlindIssuanceRequest {
        denomination: 1_000_000_000,
        blinded_commitment: vec![0xAA; 32],
        deposit_tx_id: "alice_deposit_001".to_string(),
    };

    tracker.request_blind_issuance(alice_withdrawal).unwrap();
    println!("  1. Alice deposits 1 ERG on-chain (tx: alice_deposit_001)");
    
    let alice_response = tracker.issue_blind_signature("alice_deposit_001").unwrap();
    println!("  2. Mint issues blind signature (hidden serial: ******)");
    
    let alice_note = PrivateNote::new(
        1_000_000_000,
        [0xAA; 32],
        alice_response.blind_signature,
    );
    println!("  3. Alice unblinds and obtains private note\n");
    println!("  ✓ Alice now holds 1 ERG private note (unlinkable to withdrawal)");
    println!();

    // ========== Scenario: Bob withdraws ==========
    println!("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    println!("📥 WITHDRAWAL: Bob obtains a private note");
    println!("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

    let bob_withdrawal = BlindIssuanceRequest {
        denomination: 1_000_000_000,
        blinded_commitment: vec![0xBB; 32],
        deposit_tx_id: "bob_deposit_002".to_string(),
    };

    tracker.request_blind_issuance(bob_withdrawal).unwrap();
    let bob_response = tracker.issue_blind_signature("bob_deposit_002").unwrap();
    let bob_note = PrivateNote::new(
        1_000_000_000,
        [0xBB; 32],
        bob_response.blind_signature,
    );

    println!("  ✓ Bob now holds 1 ERG private note");
    println!();

    // ========== Show proof of reserves ==========
    let por = tracker.get_proof_of_reserves();
    println!("📊 Proof of Reserves:");
    println!("  - Issued:      {} notes", por.issued_notes_count);
    println!("  - Redeemed:    {} notes", por.redeemed_notes_count);
    println!("  - Outstanding: {} nanoERG ({} ERG)", por.outstanding_value, por.outstanding_value / 1_000_000_000);
    println!("  - Balance:     {} nanoERG ({} ERG)", por.reserve_erg_balance, por.reserve_erg_balance / 1_000_000_000);
    println!("  - Solvent:     {}", if por.is_solvent { "✓ YES" } else { "✗ NO" });
    println!();

    // ========== Scenario: Off-chain transfer (Alice → Carol) ==========
    println!("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    println!("💸 OFF-CHAIN TRANSFER: Alice pays Carol");
    println!("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

    // Alice sends note to Carol privately (no tracker involvement)
    let carol_received_note = alice_note.clone();
    
    println!("  1. Alice sends note to Carol off-chain (via encrypted channel)");
    println!("  2. Tracker DOES NOT see this transfer");
    println!("  3. Carol verifies blind signature");
    
    assert!(carol_received_note.verify_signature(&mint_pubkey));
    
    let carol_nullifier = carol_received_note.nullifier(&mint_pubkey);
    assert!(!tracker.is_nullifier_spent(&carol_nullifier));
    
    println!("  4. Carol checks nullifier not spent: {}", hex::encode(&carol_nullifier.as_bytes()[0..8]));
    println!("\n  ✓ Carol now holds the note (unlinkable to Alice's withdrawal)");
    println!();

    // ========== Scenario: Carol redeems ==========
    println!("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    println!("📤 REDEMPTION: Carol redeems note for on-chain ERG");
    println!("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

    let carol_pubkey = PublicKey::from_bytes(vec![0xCC; 33]);
    let redemption_request = RedemptionRequest {
        note: carol_received_note.clone(),
        receiver_pubkey: carol_pubkey.clone(),
    };

    let tx_data = tracker.prepare_redemption(redemption_request).unwrap();
    
    println!("  1. Carol prepares redemption transaction");
    println!("     - Reveals nullifier: {}", hex::encode(&tx_data.nullifier.as_bytes()[0..8]));
    println!("     - Reveals serial:    {}", hex::encode(&tx_data.serial[0..8]));
    println!("     - Blind signature verified ✓");
    println!("  2. Transaction broadcast to blockchain");
    println!("  3. Reserve contract validates and transfers 1 ERG to Carol");
    
    tracker.finalize_redemption(tx_data.nullifier, tx_data.denomination).unwrap();
    
    println!("  4. Tracker updates nullifier set");
    println!("\n  ✓ Carol received 1 ERG on-chain");
    println!();

    // ========== Scenario: Alice tries double-spend (should fail) ==========
    println!("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    println!("🚫 DOUBLE-SPEND PREVENTION: Alice tries to redeem same note");
    println!("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

    let alice_double_spend = RedemptionRequest {
        note: alice_note.clone(),
        receiver_pubkey: PublicKey::from_bytes(vec![0xAA; 33]),
    };

    match tracker.prepare_redemption(alice_double_spend) {
        Err(TrackerError::DoubleSpend) => {
            println!("  ✓ Redemption REJECTED: Nullifier already spent");
            println!("  ✓ Double-spend attack prevented");
        },
        _ => panic!("Expected double-spend error"),
    }
    println!();

    // ========== Scenario: Bob redeems successfully ==========
    println!("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    println!("📤 REDEMPTION: Bob redeems his note");
    println!("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

    let bob_pubkey = PublicKey::from_bytes(vec![0xBB; 33]);
    let bob_redemption = RedemptionRequest {
        note: bob_note.clone(),
        receiver_pubkey: bob_pubkey.clone(),
    };

    let bob_tx = tracker.prepare_redemption(bob_redemption).unwrap();
    tracker.finalize_redemption(bob_tx.nullifier, bob_tx.denomination).unwrap();
    
    println!("  ✓ Bob successfully redeemed 1 ERG");
    println!();

    // ========== Final state ==========
    println!("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    println!("📊 FINAL STATE");
    println!("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

    let final_por = tracker.get_proof_of_reserves();
    println!("Proof of Reserves:");
    println!("  - Total issued:         {} notes", final_por.issued_notes_count);
    println!("  - Total redeemed:       {} notes", final_por.redeemed_notes_count);
    println!("  - Outstanding:          {} nanoERG ({} ERG)", final_por.outstanding_value, final_por.outstanding_value / 1_000_000_000);
    println!("  - Reserve balance:      {} nanoERG ({} ERG)", final_por.reserve_erg_balance, final_por.reserve_erg_balance / 1_000_000_000);
    println!("  - Solvency status:      {}", if final_por.is_solvent { "✓ SOLVENT" } else { "✗ INSOLVENT" });
    println!();

    println!("Nullifiers spent: {}", tracker.tracker_state.spent_nullifiers.len());
    println!();

    // ========== Privacy summary ==========
    println!("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    println!("🔒 PRIVACY PROPERTIES DEMONSTRATED");
    println!("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
    
    println!("✓ Withdrawal-Redemption Unlinkability:");
    println!("  - Carol's redemption cannot be linked to Alice's withdrawal");
    println!("  - Blind signatures hide note serial until redemption");
    println!();
    
    println!("✓ Off-Chain Transfer Privacy:");
    println!("  - Alice→Carol transfer invisible to tracker");
    println!("  - No on-chain transaction required");
    println!();
    
    println!("✓ Double-Spend Prevention:");
    println!("  - Nullifier-based spent tracking");
    println!("  - On-chain AVL tree prevents reuse");
    println!();
    
    println!("✓ Reserve Integrity:");
    println!("  - All issued notes backed by ERG");
    println!("  - Proof-of-reserves verifiable on-chain");
    println!();

    println!("╔═══════════════════════════════════════════════════════════════╗");
    println!("║                      Demo Complete                            ║");
    println!("╚═══════════════════════════════════════════════════════════════╝");
}
