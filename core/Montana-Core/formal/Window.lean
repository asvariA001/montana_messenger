-- The closure of a window, by machine: the arithmetic of the quorum (WINDOW-THEOREM.md, Lemma 4
-- and its corollary) and the uniqueness of the decision of a window (Theorem 1). Core Lean 4 only;
-- the cryptographic assumptions (A1)-(A6) enter as the hypotheses `meet` and `Faithful`, the
-- conduct of honest machines (A8) as `Honest`.

namespace Window

-- Lemma 4: two quorums of an electorate of T under the integer rule of close.rs (100 c ≥ 67 T),
-- whose union stays within T, meet in at least 34 T / 100 parts.
theorem quorum_intersection (T c1 c2 i : Nat)
    (h1 : 100 * c1 ≥ 67 * T) (h2 : 100 * c2 ≥ 67 * T)
    (union : c1 + c2 ≤ T + i) : 100 * i ≥ 34 * T := by
  omega

-- The corollary: under the fault bound 100 f < 34 T the meeting holds more parts than the
-- adversary holds secrets, so one of them is honest.
theorem more_than_the_faulty (T c1 c2 i f : Nat)
    (h1 : 100 * c1 ≥ 67 * T) (h2 : 100 * c2 ≥ 67 * T)
    (union : c1 + c2 ≤ T + i) (bound : 100 * f < 34 * T) : f < i := by
  omega

-- The frozen vectors of close.rs:116-119 under the same rule; the third is the one a rule
-- dividing first would get wrong.
theorem the_vectors_of_the_close :
    100 * 67 ≥ 67 * 100 ∧ ¬ 100 * 66 ≥ 67 * 100 ∧ ¬ 100 * 2 ≥ 67 * 3 ∧ 100 * 100 ≥ 67 * 100 := by
  decide

-- What honest parts signed, whichever verifier holds it: open pledges (attempt, part, name, the
-- attempt stood on or none) and closing pledges (attempt, part, name).
structure Signed (Part Name : Type) where
  opened : Nat → Part → Name → Option Nat → Prop
  closed : Nat → Part → Name → Prop

-- (A8): the contradicting pairs of agreement.rs:70-89, which no honest part signs.
structure Honest {Part Name : Type} (honest : Part → Prop) (σ : Signed Part Name) : Prop where
  one_open : ∀ e p n n' s s', honest p → σ.opened e p n s → σ.opened e p n' s' → n = n'
  one_close : ∀ e p n n', honest p → σ.closed e p n → σ.closed e p n' → n = n'
  no_unlock : ∀ a e p n m s, honest p → σ.closed a p n → σ.opened e p m s → a < e → m ≠ n →
    (s = none ∨ ∃ e', s = some e' ∧ e' < a) → False

-- A book: what one verifier counted (agreement.rs:91-99), with its open quorums and decisions.
structure Book (Part Name : Type) where
  opened : Nat → Part → Name → Option Nat → Prop
  closed : Nat → Part → Name → Prop
  openQuorum : Nat → Name → Prop
  decided : Nat → Name → Prop

-- The counting of agreement.rs:165-210: an open quorum is a quorum of open pledges each standing
-- on none or on an earlier open quorum of its name; a decision is a quorum of closing pledges of
-- an attempt whose open pledges reached the quorum on the same name.
structure Counts {Part Name : Type} (quorum : (Part → Prop) → Prop) (B : Book Part Name) : Prop where
  open_unfolds : ∀ e n, B.openQuorum e n → ∃ S, quorum S ∧ ∀ p, S p →
    ∃ s, B.opened e p n s ∧ (s = none ∨ ∃ e', s = some e' ∧ e' < e ∧ B.openQuorum e' n)
  decided_unfolds : ∀ a n, B.decided a n →
    B.openQuorum a n ∧ ∃ S, quorum S ∧ ∀ p, S p → B.closed a p n

-- (A2): a pledge of an honest part a verifier counts is one that part signed.
structure Faithful {Part Name : Type} (honest : Part → Prop) (σ : Signed Part Name)
    (B : Book Part Name) : Prop where
  opened : ∀ e p n s, honest p → B.opened e p n s → σ.opened e p n s
  closed : ∀ e p n, honest p → B.closed e p n → σ.closed e p n

section Agreement

variable {Part Name : Type} {quorum : (Part → Prop) → Prop} {honest : Part → Prop}
  {σ : Signed Part Name}

-- The heart of Theorem 1: once a book decided X at attempt a, no book holds an open quorum on
-- another name at a or after it.
theorem no_other_open_quorum (good : Honest honest σ)
    (meet : ∀ S1 S2, quorum S1 → quorum S2 → ∃ p, honest p ∧ S1 p ∧ S2 p)
    (B1 B : Book Part Name) (c1 : Counts quorum B1) (c : Counts quorum B)
    (f1 : Faithful honest σ B1) (f : Faithful honest σ B)
    (a : Nat) (X : Name) (hd : B1.decided a X) :
    ∀ k e, e < k → a ≤ e → ∀ Z, Z ≠ X → B.openQuorum e Z → False := by
  intro k
  induction k
  · intro e he
    exact absurd he (Nat.not_lt_zero e)
  · rename_i k ih
    intro e he hae Z hZ hoq
    obtain ⟨hoq1, S1, hq1, hS1⟩ := c1.decided_unfolds a X hd
    obtain ⟨S2, hq2, hS2⟩ := c.open_unfolds e Z hoq
    obtain ⟨h, hh, h1, h2⟩ := meet S1 S2 hq1 hq2
    have hcl := f1.closed a h X hh (hS1 h h1)
    obtain ⟨s, hop, hs⟩ := hS2 h h2
    have hsop := f.opened e h Z s hh hop
    by_cases hea : e = a
    · obtain ⟨S3, hq3, hS3⟩ := c1.open_unfolds a X hoq1
      obtain ⟨g, hg, g3, g2⟩ := meet S3 S2 hq3 hq2
      obtain ⟨s3, op3, _⟩ := hS3 g g3
      obtain ⟨s2, op2, _⟩ := hS2 g g2
      have o3 := f1.opened a g X s3 hg op3
      have o2 := f.opened e g Z s2 hg op2
      rw [hea] at o2
      exact hZ (good.one_open a g X Z s3 s2 hg o3 o2).symm
    · have hlt : a < e := by omega
      apply Or.elim hs
      · intro hnone
        exact good.no_unlock a e h X Z s hh hcl hsop hlt hZ (Or.inl hnone)
      · intro hstands
        obtain ⟨e', hse, hlt', hoq'⟩ := hstands
        by_cases hbefore : e' < a
        · exact good.no_unlock a e h X Z s hh hcl hsop hlt hZ (Or.inr ⟨e', hse, hbefore⟩)
        · exact ih e' (by omega) (by omega) Z hZ hoq'

-- Theorem 1: two books of one window over one electorate decide one name.
theorem one_decision (good : Honest honest σ)
    (meet : ∀ S1 S2, quorum S1 → quorum S2 → ∃ p, honest p ∧ S1 p ∧ S2 p)
    (B1 B2 : Book Part Name) (c1 : Counts quorum B1) (c2 : Counts quorum B2)
    (f1 : Faithful honest σ B1) (f2 : Faithful honest σ B2)
    (a b : Nat) (X Y : Name) (d1 : B1.decided a X) (d2 : B2.decided b Y) : X = Y := by
  apply Classical.byContradiction
  intro hxy
  by_cases hab : a < b
  · obtain ⟨hoq2, _⟩ := c2.decided_unfolds b Y d2
    exact no_other_open_quorum good meet B1 B2 c1 c2 f1 f2 a X d1 (b + 1) b
      (Nat.lt_succ_self b) (Nat.le_of_lt hab) Y (fun h ↦ hxy h.symm) hoq2
  · by_cases hba : b < a
    · obtain ⟨hoq1, _⟩ := c1.decided_unfolds a X d1
      exact no_other_open_quorum good meet B2 B1 c2 c1 f2 f1 b Y d2 (a + 1) a
        (Nat.lt_succ_self a) (Nat.le_of_lt hba) X hxy hoq1
    · have heq : a = b := by omega
      obtain ⟨_, S1, hq1, hS1⟩ := c1.decided_unfolds a X d1
      obtain ⟨_, S2, hq2, hS2⟩ := c2.decided_unfolds b Y d2
      obtain ⟨h, hh, h1, h2⟩ := meet S1 S2 hq1 hq2
      have k1 := f1.closed a h X hh (hS1 h h1)
      have k2 := f2.closed b h Y hh (hS2 h h2)
      rw [← heq] at k2
      exact hxy (good.one_close a h X Y hh k1 k2)

end Agreement

end Window
