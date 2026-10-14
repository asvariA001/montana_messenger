//! The actual heavy-attestation verifier currently authenticates presence,
//! but its proof has no authenticated standing commitment in its input.
//! Reproducing that omission is not a full-window acceptance test.

use montana_node::attest::{verify_heavy, Standing};
use montana_node::pulse::Cohort;
use mt_suite::standing::{weight_commit, Blind};

#[test]
fn open_finding_one_presence_proof_accepts_conflicting_standing_commitments() {
    let secret = [0x73u8; 32];
    let window = 1_000;
    let cohort = Cohort::of(&[Cohort::half_of(&secret)]).expect("one admitted machine");
    let branch = cohort.branch_of(&secret).expect("membership");
    let mut standing =
        Standing::of_the_window(&secret, window, &branch.siblings, branch.position, 1)
            .expect("a real presence proof");
    let beacon = [0x11; 32];
    let parent = [0x22; 32];
    let aggregate = [0x33; 32];
    let threshold = mt_pulse::U256::from_be_bytes(&[0xff; 32]);
    let original = standing.heavy(&secret, beacon, parent).expect("signed");
    verify_heavy(&original, &cohort.root(), &aggregate, 0, 0, &threshold)
        .expect("the ordinary attestation verifies");

    // Each alternative uses the real blinding expansion and is signed by the
    // admitted owner. The same presence proof is reused without modification.
    // The verifier receives no authenticated weight context that could decide
    // which of these contradictory commitments is allowed for this member.
    for (value, blind) in [(0, [0x41; 32]), (u64::MAX, [0x42; 32])] {
        standing.commitment = weight_commit(value, 0, window, Blind::of(blind, window))
            .expect("bounded expanded randomness")
            .serialize();
        let changed = standing
            .heavy(&secret, beacon, parent)
            .expect("resigned by its owner");
        assert_ne!(changed.weight_commit, original.weight_commit);
        assert_eq!(changed.proof, original.proof);
        assert_eq!(changed.part_nullifier, original.part_nullifier);
        assert_eq!(changed.answering_key, original.answering_key);
        verify_heavy(&changed, &cohort.root(), &aggregate, 0, 0, &threshold)
            .expect("OPEN: changed weight is accepted with the same presence proof");
    }
    println!("OPEN FINDING: the actual heavy-attestation verifier accepts commitments to zero and u64::MAX with one unchanged presence proof and member context");
}
