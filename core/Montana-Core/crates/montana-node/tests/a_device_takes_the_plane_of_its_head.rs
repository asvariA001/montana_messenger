// Two machines live four windows of a network they open, a third that holds nothing walks to their
// head from an anchor its operator hands it, and then takes the tables of the value plane of that
// head from one of them.
//
// **What is measured.** Consensus, "The join", step 5: the device "takes the state it needs against
// the roots of the head ... and it recomputes every root it takes rather than accepting one"; and
// its invariant, "Every leaf of state a joining device takes is checked against a root the verified
// head carries, by recomputation; a leaf accepted on the word of whoever answered is a defect". The
// roots are the seven and the count Canon names in "The roots of state", recomputed by the one fold
// of each table the pulse proposes with, and compared with the proposal the walk kept as the head.
//
// **The wrong implementations it refuses**, named before the assertions, each by the step it fails
// at: a device that judges the tables against the proposal before the head — the honest tables of the
// head are refused (V1); a device that takes the tables it is offered without recomputing them — a
// server's plane of its own making, whose roots are its own and not the head's, is taken (V2); a
// device that recomputes every root but the root of the tree of credited rights — one leaf of that
// tree altered under the honest roots is taken (V3); and a device that judges the honest tables and
// does not hold them, or holds them only for the life of its process — its roots are not the chain's
// after the take, or not after a restart (V4).

use montana_node::certificate::Anchor;
use montana_node::pulse::{self, Cohort};
use montana_node::state::State;
use montana_node::{Configuration, Machine, NodeError};
use mt_state::layout::Proposal;

mod common;
use common::Scratch;

// The window the network opens at, the windows it lives and the patience of a round, as the test of
// the walk beside this one holds them.
const FROM: u64 = 0;
const WINDOWS: u64 = 4;
const PATIENCE: u32 = 40_000;

fn refused(what: &'static str) -> Result<(), NodeError> {
    Err(NodeError::Refused { what })
}

// The seven roots and the count a plane gives for `window` against `cohort`, in the order a
// proposal carries them.
fn roots_of(plane: &State, cohort: &Cohort, window: u64) -> ([[u8; 32]; 7], u64) {
    let roots = plane
        .roots(cohort, window, &[])
        .expect("the roots of a plane");
    (
        [
            roots.note_root,
            roots.nullifier_root,
            roots.record_root,
            roots.machine_root,
            roots.admitted_root,
            roots.credited_root,
            roots.operations_root,
        ],
        roots.admitted_count,
    )
}

fn carried_by(proposal: &Proposal) -> ([[u8; 32]; 7], u64) {
    (
        [
            proposal.note_root,
            proposal.nullifier_root,
            proposal.record_root,
            proposal.machine_root,
            proposal.admitted_root,
            proposal.credited_root,
            proposal.operations_root,
        ],
        proposal.admitted_count,
    )
}

#[test]
fn a_device_that_walked_to_the_head_takes_its_tables_against_its_roots() {
    let far_at = Scratch::named("plane-answerer");
    let near_at = Scratch::named("plane-dialler");
    let joining_at = Scratch::named("plane-joining");
    let (far, _) = Machine::start(&Configuration::at(&far_at.0, "127.0.0.1:0"))
        .expect("the answering machine starts");
    let (near, _) = Machine::start(&Configuration::at(&near_at.0, "127.0.0.1:0"))
        .expect("the dialling machine starts");
    let halves = [
        far.naming_half().expect("a half of the answerer"),
        near.naming_half().expect("a half of the dialler"),
    ];
    let line = far.acquaintance().expect("an acquaintance is written");
    let answering = std::thread::spawn(move || {
        far.answer_one().expect("a link is answered");
        far
    });
    near.reach_and_hold(&line).expect("the answerer answers");
    let far = answering.join().expect("the thread stands");
    let living = std::thread::spawn({
        let cohort = Cohort::of(&halves).expect("the cohort");
        move || {
            let lived = pulse::live(&far, &cohort, FROM, WINDOWS, PATIENCE);
            (far, lived)
        }
    });
    let cohort = Cohort::of(&halves).expect("the cohort");
    pulse::live(&near, &cohort, FROM, WINDOWS, PATIENCE).expect("the dialler lives the windows");
    let (far, theirs) = living.join().expect("the thread stands");
    theirs.expect("the answerer lives the windows");

    // The device walks to the head, as the test of the walk measures it.
    let (joining, _) = Machine::start(&Configuration::at(&joining_at.0, "127.0.0.1:0"))
        .expect("the joining device starts");
    let line = far.acquaintance().expect("an acquaintance is written");
    let answering = std::thread::spawn(move || {
        far.answer_one().expect("the joining device is answered");
        far
    });
    joining
        .reach_and_hold(&line)
        .expect("the answerer answers the joining device");
    let far = answering.join().expect("the thread stands");
    let anchor = Anchor {
        earlier: far
            .proposal_of(FROM)
            .expect("the store answers")
            .expect("the first proposal"),
        later: far
            .proposal_of(FROM + 1)
            .expect("the store answers")
            .expect("the second proposal"),
    };
    let head = pulse::join(&joining, &anchor, PATIENCE).expect("the walk reaches the head");
    let at = FROM + WINDOWS - 2;
    assert_eq!(head.later.window, at);

    // Walked and holding no plane: the roots of the device are not the chain's (b4802f0). Its plane
    // stands against no tree of admitted machines, and its tree of credited rights is empty.
    let chain = carried_by(&head.later);
    let held_nothing = |machine: &Machine| {
        let plane = machine.value().expect("the plane");
        plane.admitted() != cohort.root() && plane.credited().bytes() != head.later.credited_root
    };
    assert!(held_nothing(&joining));

    // What the answerer holds of the plane: the decided state of the head, and its plane of the last
    // window it closed, whose proposal awaits a certificate nobody lived.
    let honest = far
        .plane_of(at)
        .expect("the store answers")
        .expect("the plane of the head");
    assert_eq!(roots_of(&honest, &cohort, at), chain);
    let offered = honest.encode().expect("the plane encodes");

    // **V2. A server's plane of its own making is refused**, though its roots are its own: one note
    // more than the chain holds, so the plane answers for roots no certificate made final.
    let mut own = honest.clone();
    own.witness(&cohort.root().bytes())
        .expect("a note of the server's own");
    assert_ne!(roots_of(&own, &cohort, at), chain);
    assert_eq!(
        pulse::take_the_plane(&joining, &cohort, &own.encode().expect("the plane encodes")),
        refused("a tree of notes whose root is not the head's")
    );
    // **V3. One leaf altered under the honest roots is refused at its table.** The tree of credited
    // rights is the last table of the encoding (state.rs, State::encode), and the head holds the
    // parts of two cements in it; its last leaf is replaced by another digest of the family.
    let mut altered = offered.clone();
    let end = altered.len();
    assert_ne!(
        &altered[end - 32..],
        &cohort.root().bytes()[..],
        "the leaf stands altered"
    );
    altered[end - 32..].copy_from_slice(&cohort.root().bytes());
    assert_eq!(
        State::decode(&altered)
            .map(|plane| roots_of(&plane, &cohort, at).0[5] != chain.0[5])
            .ok(),
        Some(true),
        "the altered plane reads whole and its tree of credited rights moved"
    );
    assert_eq!(
        pulse::take_the_plane(&joining, &cohort, &altered),
        refused("a tree of credited rights whose root is not the head's")
    );

    // **V2, again.** The plane of the window after the head is refused, whose roots its own proposal
    // carries but no certificate made final: "a proposal awaiting its certificate is not final, and
    // nothing is taken against its roots" (Consensus, "The join", step 4).
    let after = far
        .plane_of(at + 1)
        .expect("the store answers")
        .expect("the plane of the last window closed");
    assert_eq!(
        roots_of(&after, &cohort, at + 1),
        carried_by(
            &far.proposal_of(at + 1)
                .expect("the store answers")
                .expect("the proposal of the last window closed")
        )
    );
    assert_eq!(
        pulse::take_the_plane(
            &joining,
            &cohort,
            &after.encode().expect("the plane encodes")
        ),
        refused("a tree of credited rights whose root is not the head's")
    );

    // Three refusals left the device as it was.
    assert!(held_nothing(&joining));

    // **V1. The honest tables of the head are taken**, every root recomputed against the head the
    // walk kept.
    assert_eq!(pulse::take_the_plane(&joining, &cohort, &offered), Ok(()));

    // **V4. The device that took them ends with the chain's roots**, and holds them after a restart.
    assert_eq!(
        roots_of(&joining.value().expect("the plane"), &cohort, at),
        chain
    );
    assert_eq!(
        roots_of(&joining.value().expect("the plane"), &cohort, at),
        roots_of(&honest, &cohort, at)
    );
    assert_eq!(joining.head().expect("the store answers"), Some(at));
    drop(joining);
    let (again, born) = Machine::start(&Configuration::at(&joining_at.0, "127.0.0.1:0"))
        .expect("the joined device starts again");
    assert!(!born, "a restart drew a second secret");
    assert_eq!(
        roots_of(&again.value().expect("the plane"), &cohort, at),
        chain
    );
    assert_eq!(again.head().expect("the store answers"), Some(at));
    drop(far);
}
