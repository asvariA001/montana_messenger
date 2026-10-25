// Theorem 1 by enumeration (the author's decision 176, "both", 25.10.2026): the bound of the Kani
// harness `two_books_of_one_window_never_decide_two_names` walked whole, every case run on the book
// itself. The harness is the proof of record; this is the fact beside it.
//
// The bound is the harness's: four parts, the first three honest and the same in both books, the
// fourth free in each; two attempts; names 1 and 2 (0 is a pledge not made); committee 0. What one
// part puts into a book is a content: its heavy attestation, its closing pledges of attempts 0 and
// 1 and its open pledge of attempt 1 (attempt 0 has none but the heavy one), each present or not:
// 3 x 3 x 3 x 5 = 135 contents. A script of the harness is the content holding all it says, and a
// book takes of it exactly the contents below it, item by item, as the harness's `kani::any()`
// choices do. So the walk is: the decision of every book of four contents (135^4), folded over the
// free fourth part, then over the contents below each honest script; two names under one triple of
// honest scripts would be a counterexample. Run: cargo test --release -p montana-node every_book --
// --ignored --nocapture.
//
// `part`, `pledge` and `honest` are the harness's own (agreement.rs, mod proofs), copied while its
// verdicts run on that blob; one module for both comes after them (stage 6 of issue #4).

use super::*;

const WINDOW: u64 = 7;
const PARTS: usize = 4;
const FAULTY: usize = 3;
const ATTEMPTS: u32 = 2;
const CONTENTS: usize = 3 * 3 * 3 * 5;

#[derive(Clone, Copy, PartialEq, Eq)]
struct Content {
    heavy: u8,
    closing: [u8; ATTEMPTS as usize],
    // 0: none; else 1 + 2 (name - 1) + s, where s = 0 stands on none and s = 1 on attempt 0.
    open: u8,
}

fn content(i: usize) -> Content {
    Content {
        heavy: (i % 3) as u8,
        closing: [(i / 3 % 3) as u8, (i / 9 % 3) as u8],
        open: (i / 27) as u8,
    }
}

fn open_said(open: u8) -> Option<(u8, u32)> {
    let k = open.checked_sub(1)?;
    Some((k / 2 + 1, if k % 2 == 0 { STANDS_ON_NONE } else { 0 }))
}

// The contents a book may take of `script`: each item either absent or the script's own.
fn below(c: Content, script: Content) -> bool {
    let item = |a: u8, b: u8| a == 0 || a == b;
    item(c.heavy, script.heavy)
        && item(c.closing[0], script.closing[0])
        && item(c.closing[1], script.closing[1])
        && item(c.open, script.open)
}

fn part(at: usize) -> Part {
    [at as u8 + 1; 32]
}

fn pledge(at: usize, attempt: u32, name: u8, since: u32) -> Pledge {
    Pledge {
        window: WINDOW,
        attempt,
        name: [name; 32],
        since,
        part_nullifier: part(at),
        suite_id: 0,
        answering_key: Vec::new(),
        signature: Vec::new(),
    }
}

// The honest predicate of the harness, on a script.
fn honest(at: usize, script: Content) -> bool {
    let open = [None, open_said(script.open)];
    (0..ATTEMPTS).all(|closed| {
        let name = script.closing[closed as usize];
        (0..ATTEMPTS).all(|later| match open[later as usize] {
            None => true,
            Some(_) if name == 0 => true,
            Some((open_name, since)) => !contradicts(
                &pledge(at, closed, name, closed),
                &pledge(at, later, open_name, since),
            ),
        })
    })
}

// A part's content into the book, in the harness's order: heavy, then per attempt closing and open.
fn put(book: &mut Book, at: usize, c: Content) {
    if c.heavy != 0 {
        book.heavy(Place::default(), part(at), [c.heavy; 32]);
    }
    for attempt in 0..ATTEMPTS {
        let closing = c.closing[attempt as usize];
        if closing != 0 {
            book.pledge(pledge(at, attempt, closing, attempt));
        }
        if attempt == 1 {
            if let Some((name, since)) = open_said(c.open) {
                book.pledge(pledge(at, attempt, name, since));
            }
        }
    }
}

// The decision of one book as a bit: 1 for name 1, 2 for name 2, 0 for none.
fn decision(contents: [usize; PARTS], electorate: &Electorate<'_>) -> u8 {
    let mut book = Book::of_the_window(WINDOW);
    for (at, &c) in contents.iter().enumerate() {
        put(&mut book, at, content(c));
    }
    match book.decided(electorate) {
        None => 0,
        Some((name, _)) => {
            assert!(name == [name[0]; 32] && (1..=2).contains(&name[0]), "a name no part pledged");
            1 << (name[0] - 1)
        }
    }
}

#[test]
#[ignore = "the whole bound of Theorem 1: minutes in release; run with --ignored"]
fn every_book_of_the_bound_decides_at_most_one_name() {
    let index = |a: usize, b: usize, c: usize| (a * CONTENTS + b) * CONTENTS + c;
    // The free part folded away: the names any fourth content lets the first three decide.
    let threads = std::thread::available_parallelism().map_or(1, |n| n.get());
    let mut free = vec![0u8; CONTENTS * CONTENTS * CONTENTS];
    let mut deciding = 0u64;
    std::thread::scope(|s| {
        let rows: Vec<_> = (0..threads)
            .map(|t| {
                s.spawn(move || {
                    let votes = BTreeMap::new();
                    let electorate = Electorate {
                        count: PARTS as u64,
                        committee: 0,
                        votes: &votes,
                    };
                    let mut out = Vec::new();
                    let mut count = 0u64;
                    for a in (t..CONTENTS).step_by(threads) {
                        for b in 0..CONTENTS {
                            for c in 0..CONTENTS {
                                let mut bits = 0;
                                for d in 0..CONTENTS {
                                    let bit = decision([a, b, c, d], &electorate);
                                    count += u64::from(bit != 0);
                                    bits |= bit;
                                }
                                out.push((index(a, b, c), bits));
                            }
                        }
                    }
                    (out, count)
                })
            })
            .collect();
        for row in rows {
            let (out, count) = row.join().expect("a walker failed");
            deciding += count;
            for (i, bits) in out {
                free[i] = bits;
            }
        }
    });
    // Then each honest part: from contents to the scripts above them.
    let scripts: Vec<Content> = (0..CONTENTS).map(content).collect();
    let fold = |table: &[u8], axis: usize| -> Vec<u8> {
        let mut out = vec![0u8; table.len()];
        for (i, slot) in out.iter_mut().enumerate() {
            let mut at = [i / (CONTENTS * CONTENTS), i / CONTENTS % CONTENTS, i % CONTENTS];
            let script = scripts[at[axis]];
            for (c, &taken) in scripts.iter().enumerate() {
                if below(taken, script) {
                    at[axis] = c;
                    *slot |= table[index(at[0], at[1], at[2])];
                }
            }
        }
        out
    };
    let held = fold(&fold(&fold(&free, 2), 1), 0);
    let honest_of: Vec<Vec<usize>> = (0..FAULTY)
        .map(|at| (0..CONTENTS).filter(|&s| honest(at, scripts[s])).collect())
        .collect();
    let mut triples = 0u64;
    let mut decided_under_honest = 0u64;
    for &x in &honest_of[0] {
        for &y in &honest_of[1] {
            for &z in &honest_of[2] {
                let bits = held[index(x, y, z)];
                triples += 1;
                decided_under_honest += u64::from(bits != 0);
                assert!(bits != 3, "two names decided under honest scripts {x}, {y}, {z}");
            }
        }
    }
    // Not vacuous: books decide under honest scripts (the harness's cover), and without the honest
    // predicate the same walk does find two names, so the predicate is what the theorem rests on.
    let dishonest_split = held.iter().filter(|&&bits| bits == 3).count();
    println!(
        "books {}, deciding {deciding}; honest scripts {} {} {}; honest triples {triples}, \
         deciding under them {decided_under_honest}; triples of any scripts deciding both names {dishonest_split}",
        CONTENTS.pow(4),
        honest_of[0].len(),
        honest_of[1].len(),
        honest_of[2].len(),
    );
    assert!(decided_under_honest != 0, "no book decides: the walk proves nothing");
    assert!(dishonest_split != 0, "two names never arise even without honesty: the walk is too weak");
}
