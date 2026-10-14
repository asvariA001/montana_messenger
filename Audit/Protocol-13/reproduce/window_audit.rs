//! Reproductions of open findings, not finality conformance tests.
//!
//! The generic proof verifier authenticates the described arithmetic only. These
//! cases establish why it must not be used as a complete window verifier. Once
//! the complete statement exists, its verifier must reject these witnesses.

use mt_proof::circuit::{fold, window};
use mt_proof::{poseidon, scheme};

#[test]
fn open_finding_zero_challenges_drop_the_entire_instance() {
    let taken = [[0u32; window::INSTANCE_COEFFICIENTS]; fold::REPETITIONS];
    let mut instance = [0u32; window::INSTANCE_COEFFICIENTS];
    instance[0] = 1;
    let challenges = [0u32; fold::REPETITIONS];
    let (trace, reached) = window::trace_of(&taken, &instance, &challenges);
    assert_eq!(reached, taken);

    let public = window::public_of(&taken, &instance, &reached, &challenges);
    let description = window::description();
    assert!(description.satisfied_by(&trace, &poseidon::limbs_of(&public)));
    let proof = scheme::prove(&description, &public, &trace).expect("arithmetic proves");
    assert_eq!(scheme::verify(&description, &public, &proof), Ok(()));
    println!(
        "OPEN FINDING: verifier accepts all-zero fold challenges; a nonzero instance leaves every accumulator unchanged"
    );
}

#[test]
fn open_finding_chosen_nonzero_challenges_cancel_an_unauthenticated_parent() {
    let mut taken = [[0u32; window::INSTANCE_COEFFICIENTS]; fold::REPETITIONS];
    let mut instance = [0u32; window::INSTANCE_COEFFICIENTS];
    instance[0] = 1;
    for run in &mut taken {
        run[0] = fold::MODULUS as u32 - 1;
    }
    // Refusing zero alone does not establish transcript binding or authenticate
    // the predecessor: the claimant can choose these six challenges as well.
    let challenges = [1u32; fold::REPETITIONS];
    let (trace, reached) = window::trace_of(&taken, &instance, &challenges);
    assert_eq!(
        reached,
        [[0u32; window::INSTANCE_COEFFICIENTS]; fold::REPETITIONS]
    );

    let public = window::public_of(&taken, &instance, &reached, &challenges);
    let description = window::description();
    assert!(description.satisfied_by(&trace, &poseidon::limbs_of(&public)));
    let proof = scheme::prove(&description, &public, &trace).expect("arithmetic proves");
    assert_eq!(scheme::verify(&description, &public, &proof), Ok(()));
    println!(
        "OPEN FINDING: verifier accepts claimant-chosen challenges and parent accumulator; the result alone authenticates no history"
    );
}
