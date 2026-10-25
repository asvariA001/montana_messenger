-- The closure of a window, by machine (WINDOW-THEOREM.md, issue #4, stage 5). Core Lean 4 only:
-- no Mathlib and no axiom of its own. Proved here: Lemma 4 and its corollary and Theorem 1 (one
-- decision), section Agreement; Lemma 1 (order), section Order; Lemma 2 (exactness), sections
-- Exact and Certificate. The cryptographic assumptions (A1)-(A6) enter as hypotheses -- `meet`,
-- `Faithful`, and the opaque checks of `Cert` -- and the conduct of honest machines (A8) as
-- `Honest`. Line numbers are those of certificate.rs, agreement.rs and close.rs in this tree.

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

section Steps

-- The two shapes a refusal takes in verify_certificate: a guard that returns Err, and a `?` that
-- hands the value on.
def need {α : Type} (b : Prop) [Decidable b] (k : Option α) : Option α := if b then k else none

def seq {α β : Type} (o : Option α) (f : α → Option β) : Option β :=
  match o with
  | none => none
  | some a => f a

theorem need_some {α : Type} (b : Prop) [Decidable b] (k : Option α) (x : α) :
    need b k = some x ↔ b ∧ k = some x := by
  unfold need
  by_cases h : b <;> simp [h]

theorem seq_some {α β : Type} (o : Option α) (f : α → Option β) (x : β) :
    seq o f = some x ↔ ∃ a, o = some a ∧ f a = some x := by
  cases o <;> simp [seq]

end Steps

-- Lemma 1 (WINDOW-THEOREM.md section 4, order): the loop of verify_certificate over the
-- attestations (certificate.rs:277-318) answers Ok iff every attestation is valid in its round
-- (C2) and any two of one part carry one key (C3), so its answer does not depend on the order
-- or the repetition of the attestations handed in; and the pledged name a part keeps in attempt
-- zero, the least (place, name) of the part (agreement.rs, `Book::heavy`), is the least element
-- of a total order, which does not depend on that order either.

section Order

variable {A P K : Type} [DecidableEq P] [DecidableEq K]

-- What the loop reads of an attestation: its part, its key, the checks of its own shape before the
-- key map is read (:278-292) and the verification of its signature and presence after (:300-307);
-- both read the attestation and its round alone.
structure Reads (A P K : Type) where
  part : A → P
  key : A → K
  shaped : A → Bool
  verifies : A → Bool

-- (C2) for one attestation.
def Reads.valid (R : Reads A P K) (a : A) : Bool := R.shaped a && R.verifies a

-- The key map of the loop, newest first.
def lookup : List (P × K) → P → Option K
  | [], _ => none
  | (q, k) :: m, p => if q = p then some k else lookup m p

-- One attestation: refused when its shape fails, when the map holds another key of its part
-- (:293-299), or when it does not verify; the map is extended only after it verifies (:308-309).
def step (R : Reads A P K) (m : List (P × K)) (a : A) : Option (List (P × K)) :=
  if R.shaped a = false then none
  else match lookup m (R.part a) with
    | some k => if k = R.key a then (if R.verifies a then some m else none) else none
    | none => if R.verifies a then some ((R.part a, R.key a) :: m) else none

def run (R : Reads A P K) : List (P × K) → List A → Option (List (P × K))
  | m, [] => some m
  | m, a :: l => match step R m a with
    | none => none
    | some m' => run R m' l

-- (C3) over a list.
def OneKey (R : Reads A P K) (l : List A) : Prop :=
  ∀ a, a ∈ l → ∀ b, b ∈ l → R.part a = R.part b → R.key a = R.key b

-- The map agrees with the keys of a list.
def Agrees (R : Reads A P K) (m : List (P × K)) (l : List A) : Prop :=
  ∀ a, a ∈ l → ∀ k, lookup m (R.part a) = some k → k = R.key a

theorem ite_yes {γ : Type} {c : Prop} [Decidable c] (h : c) (x y : γ) :
    (if c then x else y) = x := by simp [h]

theorem ite_no {γ : Type} {c : Prop} [Decidable c] (h : ¬ c) (x y : γ) :
    (if c then x else y) = y := by simp [h]

omit [DecidableEq K] in
theorem lookup_cons (q p : P) (k : K) (m : List (P × K)) :
    lookup ((q, k) :: m) p = if q = p then some k else lookup m p := rfl

theorem run_ok (R : Reads A P K) : ∀ (l : List A) (m : List (P × K)),
    run R m l ≠ none ↔ (∀ a, a ∈ l → R.valid a = true) ∧ OneKey R l ∧ Agrees R m l := by
  intro l
  induction l with
  | nil =>
    intro m
    simp [run, OneKey, Agrees]
  | cons a l ih =>
    intro m
    have hrun : run R m (a :: l) = (match step R m a with
        | none => none
        | some m' => run R m' l) := rfl
    rw [hrun]
    by_cases hs : R.shaped a = false
    · have : step R m a = none := by simp [step, hs]
      rw [this]
      simp only [ne_eq, not_true_eq_false, false_iff]
      intro ⟨hv, _, _⟩
      have := hv a (List.mem_cons_self ..)
      simp [Reads.valid, hs] at this
    · have hs' : R.shaped a = true := by
        cases h : R.shaped a <;> simp_all
      cases hl : lookup m (R.part a) with
      | some k =>
        by_cases hk : k = R.key a
        · by_cases hv : R.verifies a = true
          · have : step R m a = some m := by simp [step, hs', hl, hk, hv]
            rw [this, ih m]
            constructor
            · intro ⟨hV, hC, hM⟩
              refine ⟨?_, ?_, ?_⟩
              · intro b hb
                rcases List.mem_cons.mp hb with rfl | hb
                · simp [Reads.valid, hs', hv]
                · exact hV b hb
              · intro b hb c hc hbc
                rcases List.mem_cons.mp hb with hb | hb
                · rcases List.mem_cons.mp hc with hc | hc
                  · rw [hb, hc]
                  · rw [hb] at hbc ⊢
                    have := hM c hc k (by rw [← hbc]; exact hl)
                    rw [← hk]; exact this
                · rcases List.mem_cons.mp hc with hc | hc
                  · rw [hc] at hbc ⊢
                    have := hM b hb k (by rw [hbc]; exact hl)
                    exact this.symm.trans hk
                  · exact hC b hb c hc hbc
              · intro b hb k' hk'
                rcases List.mem_cons.mp hb with rfl | hb
                · rw [hl] at hk'; cases hk'; exact hk
                · exact hM b hb k' hk'
            · intro ⟨hV, hC, hM⟩
              exact ⟨fun b hb ↦ hV b (List.mem_cons_of_mem _ hb),
                fun b hb c hc ↦ hC b (List.mem_cons_of_mem _ hb) c (List.mem_cons_of_mem _ hc),
                fun b hb ↦ hM b (List.mem_cons_of_mem _ hb)⟩
          · have : step R m a = none := by
              have hv' : R.verifies a = false := by cases h : R.verifies a <;> simp_all
              simp [step, hs', hl, hk, hv']
            rw [this]
            simp only [ne_eq, not_true_eq_false, false_iff]
            intro ⟨hV, _, _⟩
            have := hV a (List.mem_cons_self ..)
            simp [Reads.valid, hs'] at this
            exact hv this
        · have : step R m a = none := by simp [step, hs', hl, hk]
          rw [this]
          simp only [ne_eq, not_true_eq_false, false_iff]
          intro ⟨_, _, hM⟩
          exact hk (hM a (List.mem_cons_self ..) k hl)
      | none =>
        by_cases hv : R.verifies a = true
        · have : step R m a = some ((R.part a, R.key a) :: m) := by simp [step, hs', hl, hv]
          rw [this, ih]
          constructor
          · intro ⟨hV, hC, hM⟩
            refine ⟨?_, ?_, ?_⟩
            · intro b hb
              rcases List.mem_cons.mp hb with rfl | hb
              · simp [Reads.valid, hs', hv]
              · exact hV b hb
            · intro b hb c hc hbc
              rcases List.mem_cons.mp hb with hb | hb
              · rcases List.mem_cons.mp hc with hc | hc
                · rw [hb, hc]
                · rw [hb] at hbc ⊢
                  exact hM c hc (R.key a) (by rw [lookup_cons, ite_yes hbc])
              · rcases List.mem_cons.mp hc with hc | hc
                · rw [hc] at hbc ⊢
                  exact (hM b hb (R.key a) (by rw [lookup_cons, ite_yes hbc.symm])).symm
                · exact hC b hb c hc hbc
            · intro b hb k' hk'
              rcases List.mem_cons.mp hb with rfl | hb
              · rw [hl] at hk'; cases hk'
              · by_cases hab : R.part a = R.part b
                · rw [← hab, hl] at hk'; cases hk'
                · exact hM b hb k' (by rw [lookup_cons, ite_no hab]; exact hk')
          · intro ⟨hV, hC, hM⟩
            refine ⟨fun b hb ↦ hV b (List.mem_cons_of_mem _ hb),
              fun b hb c hc ↦ hC b (List.mem_cons_of_mem _ hb) c (List.mem_cons_of_mem _ hc), ?_⟩
            intro b hb k' hk'
            rw [lookup_cons] at hk'
            by_cases hab : R.part a = R.part b
            · rw [ite_yes hab] at hk'; cases hk'
              exact hC a (List.mem_cons_self ..) b (List.mem_cons_of_mem _ hb) hab
            · rw [ite_no hab] at hk'
              exact hM b (List.mem_cons_of_mem _ hb) k' hk'
        · have : step R m a = none := by
            have hv' : R.verifies a = false := by cases h : R.verifies a <;> simp_all
            simp [step, hs', hl, hv']
          rw [this]
          simp only [ne_eq, not_true_eq_false, false_iff]
          intro ⟨hV, _, _⟩
          have := hV a (List.mem_cons_self ..)
          simp [Reads.valid, hs'] at this
          exact hv this

-- Lemma 1: the loop from an empty map answers Ok iff (C2) and (C3).
theorem lemma1 (R : Reads A P K) (l : List A) :
    run R [] l ≠ none ↔ (∀ a, a ∈ l → R.valid a = true) ∧ OneKey R l := by
  rw [run_ok]
  constructor
  · intro ⟨h1, h2, _⟩; exact ⟨h1, h2⟩
  · intro ⟨h1, h2⟩; exact ⟨h1, h2, fun _ _ _ h ↦ by simp [lookup] at h⟩

-- Where (C2) holds, the refusal of a second key fires iff (C3) fails.
theorem second_key_iff (R : Reads A P K) (l : List A) (c2 : ∀ a, a ∈ l → R.valid a = true) :
    run R [] l = none ↔ ¬ OneKey R l := by
  have := lemma1 R l
  constructor
  · intro h hk; exact (this.mpr ⟨c2, hk⟩) h
  · intro hn; apply Classical.byContradiction; intro h; exact hn (this.mp h).2

-- The answer is one for any two lists holding the same attestations, whatever their order and
-- their repetitions.
theorem order_free (R : Reads A P K) (l l' : List A) (same : ∀ a, a ∈ l ↔ a ∈ l') :
    run R [] l ≠ none ↔ run R [] l' ≠ none := by
  rw [lemma1, lemma1]
  constructor
  · intro ⟨h1, h2⟩
    exact ⟨fun a ha ↦ h1 a ((same a).mpr ha),
      fun a ha b hb ↦ h2 a ((same a).mpr ha) b ((same b).mpr hb)⟩
  · intro ⟨h1, h2⟩
    exact ⟨fun a ha ↦ h1 a ((same a).mp ha),
      fun a ha b hb ↦ h2 a ((same a).mp ha) b ((same b).mp hb)⟩


-- The key of a part as (C3) reads it: the key of its first attestation; under (C3), of every one.
def kappa (R : Reads A P K) : List A → P → Option K
  | [], _ => none
  | a :: l, p => if R.part a = p then some (R.key a) else kappa R l p

def orElse : Option K → Option K → Option K
  | some k, _ => some k
  | none, o => o

-- What one step does to the map: keeps it, the part holding this key already, or puts the part
-- with its key in front.
theorem step_some (R : Reads A P K) (m m1 : List (P × K)) (a : A) (h : step R m a = some m1) :
    (lookup m (R.part a) = some (R.key a) ∧ m1 = m) ∨
    (lookup m (R.part a) = none ∧ m1 = (R.part a, R.key a) :: m) := by
  unfold step at h
  cases hs : R.shaped a
  · simp [hs] at h
  · cases hl : lookup m (R.part a) with
    | some k =>
      cases hv : R.verifies a
      · simp [hs, hl, hv] at h
      · by_cases hk : k = R.key a
        · simp [hs, hl, hv, hk] at h
          exact Or.inl ⟨by rw [hk], h.symm⟩
        · simp [hs, hl, hk] at h
    | none =>
      cases hv : R.verifies a
      · simp [hs, hl, hv] at h
      · simp [hs, hl, hv] at h
        exact Or.inr ⟨rfl, h.symm⟩

-- The map the loop ends with reads, for every part, the key the map began with, or else the key
-- of the part's first attestation in the list.
theorem run_lookup (R : Reads A P K) : ∀ (l : List A) (m m' : List (P × K)),
    run R m l = some m' → ∀ p, lookup m' p = orElse (lookup m p) (kappa R l p) := by
  intro l
  induction l with
  | nil =>
    intro m m' h p
    have e : m = m' := Option.some.inj h
    subst e
    cases lookup m p <;> rfl
  | cons a l ih =>
    intro m m' h p
    have hrun : run R m (a :: l) = seq (step R m a) (fun m1 ↦ run R m1 l) := by
      show (match step R m a with | none => none | some m1 => run R m1 l) = _
      cases step R m a <;> rfl
    rw [hrun, seq_some] at h
    obtain ⟨m1, hs, h⟩ := h
    rw [ih m1 m' h p]
    have hk : kappa R (a :: l) p = if R.part a = p then some (R.key a) else kappa R l p := rfl
    rw [hk]
    rcases step_some R m m1 a hs with ⟨hl, e⟩ | ⟨hl, e⟩
    · subst e
      by_cases hp : R.part a = p
      · subst hp
        rw [hl]
        rfl
      · rw [ite_no hp]
    · subst e
      rw [lookup_cons]
      by_cases hp : R.part a = p
      · subst hp
        rw [hl, ite_yes rfl, ite_yes rfl]
        rfl
      · rw [ite_no hp, ite_no hp]

-- The loop over two lists is the loop over the first, then over the second from its map.
theorem run_append (R : Reads A P K) : ∀ (l1 l2 : List A) (m : List (P × K)),
    run R m (l1 ++ l2) = seq (run R m l1) (fun m' ↦ run R m' l2)
  | [], _, _ => rfl
  | a :: l1, l2, m => by
    show (match step R m a with | none => none | some m1 => run R m1 (l1 ++ l2)) =
      seq (match step R m a with | none => none | some m1 => run R m1 l1)
        (fun m' ↦ run R m' l2)
    cases step R m a with
    | none => rfl
    | some m1 => exact run_append R l1 l2 m1

omit [DecidableEq K] in
-- Under (C3), kappa is the key of every attestation of the part.
theorem kappa_key (R : Reads A P K) : ∀ (l : List A), OneKey R l → ∀ a, a ∈ l →
    kappa R l (R.part a) = some (R.key a)
  | [], _, a, ha => by simp at ha
  | b :: l, h, a, ha => by
    show (if R.part b = R.part a then some (R.key b) else kappa R l (R.part a)) = some (R.key a)
    by_cases hb : R.part b = R.part a
    · rw [ite_yes hb, h b (List.mem_cons_self ..) a ha hb]
    · rw [ite_no hb]
      rcases List.mem_cons.mp ha with e | ha'
      · exact absurd (by rw [e]) hb
      · exact kappa_key R l (fun x hx y hy ↦
          h x (List.mem_cons_of_mem _ hx) y (List.mem_cons_of_mem _ hy)) a ha'

omit [DecidableEq K] in
-- A part has a key iff an attestation of the list is of it: kappa is defined exactly on the cement.
theorem kappa_mem (R : Reads A P K) : ∀ (l : List A) (p : P),
    kappa R l p ≠ none ↔ ∃ a, a ∈ l ∧ R.part a = p
  | [], p => by simp [kappa]
  | b :: l, p => by
    show (if R.part b = p then some (R.key b) else kappa R l p) ≠ none ↔ _
    by_cases hb : R.part b = p
    · rw [ite_yes hb]
      exact ⟨fun _ ↦ ⟨b, List.mem_cons_self .., hb⟩, fun _ e ↦ nomatch e⟩
    · rw [ite_no hb, kappa_mem R l p]
      constructor
      · intro ⟨a, ha, e⟩
        exact ⟨a, List.mem_cons_of_mem _ ha, e⟩
      · intro ⟨a, ha, e⟩
        rcases List.mem_cons.mp ha with ea | ha
        · rw [ea] at e
          exact absurd e hb
        · exact ⟨a, ha, e⟩

end Order

section Least

variable {β : Type} (le : β → β → Bool)

-- The least of a nonempty list under `le`, kept the way `Book::heavy` keeps it: a newcomer
-- replaces the kept element only where it is below it.
def least : β → List β → β
  | x, [] => x
  | x, y :: l => least (if le x y then x else y) l

theorem least_spec (total : ∀ x y, le x y = true ∨ le y x = true)
    (trans : ∀ x y z, le x y = true → le y z = true → le x z = true) :
    ∀ (l : List β) (x : β),
      least le x l ∈ x :: l ∧ ∀ y, y ∈ x :: l → le (least le x l) y = true := by
  intro l
  induction l with
  | nil =>
    intro x
    refine ⟨List.mem_cons_self .., ?_⟩
    intro y hy
    rcases List.mem_cons.mp hy with rfl | hy
    · rcases total y y with h | h <;> exact h
    · cases hy
  | cons y l ih =>
    intro x
    obtain ⟨hm, hle⟩ := ih (if le x y then x else y)
    refine ⟨?_, ?_⟩
    · show least le (if le x y then x else y) l ∈ x :: y :: l
      rcases List.mem_cons.mp hm with h | h
      · rw [h]
        by_cases hxy : le x y = true
        · rw [ite_yes hxy]; exact List.mem_cons_self ..
        · rw [ite_no hxy]; exact List.mem_cons_of_mem _ (List.mem_cons_self ..)
      · exact List.mem_cons_of_mem _ (List.mem_cons_of_mem _ h)
    · intro z hz
      have hk := hle (if le x y then x else y) (List.mem_cons_self ..)
      show le (least le (if le x y then x else y) l) z = true
      rcases List.mem_cons.mp hz with hz | hz
      · rw [hz]
        by_cases hxy : le x y = true
        · rw [ite_yes hxy] at hk ⊢; exact hk
        · rw [ite_no hxy] at hk ⊢
          have hyx : le y x = true := by
            rcases total x y with h | h
            · exact absurd h hxy
            · exact h
          exact trans _ _ _ hk hyx
      · rcases List.mem_cons.mp hz with hz | hz
        · rw [hz]
          by_cases hxy : le x y = true
          · rw [ite_yes hxy] at hk ⊢; exact trans _ _ _ hk hxy
          · rw [ite_no hxy] at hk ⊢; exact hk
        · exact hle z (List.mem_cons_of_mem _ hz)

-- The least element does not depend on the order: two lists holding the same elements keep one.
theorem least_order_free (total : ∀ x y, le x y = true ∨ le y x = true)
    (trans : ∀ x y z, le x y = true → le y z = true → le x z = true)
    (antisymm : ∀ x y, le x y = true → le y x = true → x = y)
    (x x' : β) (l l' : List β) (same : ∀ z, z ∈ x :: l ↔ z ∈ x' :: l') :
    least le x l = least le x' l' := by
  obtain ⟨m1, h1⟩ := least_spec le total trans l x
  obtain ⟨m2, h2⟩ := least_spec le total trans l' x'
  exact antisymm _ _ (h1 _ ((same _).mpr m2)) (h2 _ ((same _).mp m1))

end Least

-- Lemma 2 (WINDOW-THEOREM.md section 4, exactness), its arithmetic: every product the quorum and
-- the committee rules take in u128 (close.rs) has an operand below 2^64 and a factor of at most
-- 100, so u128::checked_mul never refuses and is the integer product, and the rules read in u128
-- are the rules of section 1.

def W64 : Nat := 18446744073709551616
def W128 : Nat := W64 * W64

theorem product_fits (f x : Nat) (hf : f ≤ 100) (hx : x + 1 ≤ W64) : f * x + 1 ≤ W128 := by
  have h1 : f * x ≤ 100 * x := Nat.mul_le_mul_right x hf
  unfold W128 W64 at *
  omega

-- A product as close.rs takes it: u128::checked_mul, which refuses (CountBeyondWidth) rather
-- than wraps.
def checkedMul (f x : Nat) : Option Nat := if f * x + 1 ≤ W128 then some (f * x) else none

theorem checkedMul_exact (f x : Nat) (hf : f ≤ 100) (hx : x + 1 ≤ W64) :
    checkedMul f x = some (f * x) := by
  simp [checkedMul, product_fits f x hf hx]

-- reaches_share (close.rs:20-31) at the share 67/100 of the Decree: false for an empty electorate
-- or a cement above it, else the comparison of the two checked products.
def reachesShare (c t : Nat) : Option Bool :=
  if t = 0 ∨ t + 1 ≤ c then some false
  else match checkedMul 100 c, checkedMul 67 t with
    | some l, some r => some (decide (r ≤ l))
    | _, _ => none

-- The quorum rule Q(c, t) of section 1.
def quorumQ (c t : Nat) : Prop := t ≠ 0 ∧ c ≤ t ∧ 67 * t ≤ 100 * c

theorem reachesShare_exact (c t : Nat) (ht : t + 1 ≤ W64) :
    reachesShare c t = some true ↔ quorumQ c t := by
  unfold reachesShare quorumQ
  split
  · constructor
    · intro e; cases e
    · intro ⟨h1, h2, _⟩; omega
  · have hc : c + 1 ≤ W64 := by omega
    rw [checkedMul_exact 100 c (by decide) hc, checkedMul_exact 67 t (by decide) ht]
    simp only [Option.some.injEq, decide_eq_true_eq]
    constructor
    · intro e; exact ⟨by omega, by omega, e⟩
    · intro ⟨_, _, e⟩; exact e

-- votes_reached (close.rs:36-46): the committee rule V(v, K) of section 1, the sum of votes held
-- below 2^64 by (C5).
def votesReached (v k : Nat) : Option Bool :=
  match checkedMul 100 v, checkedMul 67 k with
    | some l, some r => some (decide (r ≤ l))
    | _, _ => none

theorem votesReached_exact (v k : Nat) (hv : v + 1 ≤ W64) (hk : k + 1 ≤ W64) :
    votesReached v k = some true ↔ 67 * k ≤ 100 * v := by
  unfold votesReached
  rw [checkedMul_exact 100 v (by decide) hv, checkedMul_exact 67 k (by decide) hk]
  simp only [Option.some.injEq, decide_eq_true_eq]

-- The tally of the votes (certificate.rs:189-195): a fold from nought by u64::checked_add, which
-- refuses at the first partial sum past the width. Partial sums only grow, so it refuses iff the
-- whole sum is past it, and otherwise it is the sum.
def checkedSum : Nat → List Nat → Option Nat
  | s, [] => some s
  | s, x :: l => if s + x + 1 ≤ W64 then checkedSum (s + x) l else none

def total : List Nat → Nat
  | [] => 0
  | x :: l => x + total l

theorem checkedSum_exact : ∀ (l : List Nat) (s : Nat), s + 1 ≤ W64 → ∀ r,
    checkedSum s l = some r ↔ r = s + total l ∧ s + total l + 1 ≤ W64
  | [], s, hs, r => by
    show some s = some r ↔ r = s + 0 ∧ s + 0 + 1 ≤ W64
    constructor
    · intro e
      cases e
      exact ⟨by omega, by omega⟩
    · intro ⟨e, _⟩
      rw [e, Nat.add_zero]
  | x :: l, s, hs, r => by
    show (if s + x + 1 ≤ W64 then checkedSum (s + x) l else none) = some r ↔
      r = s + (x + total l) ∧ s + (x + total l) + 1 ≤ W64
    by_cases h : s + x + 1 ≤ W64
    · rw [ite_yes h, checkedSum_exact l (s + x) h r, Nat.add_assoc s x]
    · rw [ite_no h]
      constructor
      · intro e
        cases e
      · intro ⟨_, h2⟩
        exact absurd (by omega : s + x + 1 ≤ W64) h

-- Lemma 2 (exactness), the composition: verify_certificate (certificate.rs:252-406) as it is
-- written, check by check in the order of the code, against Pi_cert of section 2.
--
-- Every check whose strength is the subject of an assumption of section 3 -- a signature, a proof,
-- the book, the aggregate -- is one opaque function of `Cert`, the same one in the code and in
-- Pi_cert: what is proved is that the code takes exactly the conjunction of the clauses, no clause
-- more and none less. An attestation of `A` carries its round, so `Reads` reads it as valid in its
-- round (C2.1-C2.5). Two readings of the code are taken as the model: the map of votes is read as a
-- function, nought where it holds nothing (agreement.rs:41), and summed over the cement, where its
-- absent parts add nought; and `Cement::parts` lists each part once, ascending.
structure Cert (A P K N Rd G Bl D S : Type) where
  follows : Bool                       -- Predecessor::is_followed_by(window), :261-265
  previous : Option N                  -- Predecessor::id, :266
  threshold : Bool                     -- round_threshold(T, c) defined, :267-272
  beacon : Rd → Bool                   -- the beacon is of window W and has an identifier, :278-283
  heavy : Rd → List A                  -- Round::heavy
  ballots : Rd → List Bl               -- Round::ballots
  att : Reads A P K                    -- an attestation in its round, :284-309 (Order)
  cement : List P → List P             -- Cement::parts
  ballotPart : Bl → P
  cast : Bl → Option Nat               -- verify_ballot, :363-367
  pledgePart : G → P
  pledgeOk : G → K → Bool              -- verify_pledge, :381
  count : Nat                          -- T, Electorate::count
  expected : Nat                       -- K, the votes the committee expects
  decides : List Rd → List G → (P → Nat) → Option N   -- Book::decided, :311-315, :377-394
  aggregate : List P → Option D        -- Cement::aggregate, :395-397
  answered : List Rd → (P → Option K) → S             -- the light attestations, :324-352

-- What the certificate returns (Closure, :398-405); its window is the one it was asked for.
structure Closure (P D S : Type) where
  aggregate : D
  count : Nat
  parts : List P
  votes : P → Nat
  answered : S

theorem Closure.eq_of {P D S : Type} (L : Closure P D S) (d : D) (n : Nat) (C : List P)
    (v : P → Nat) (s : S) (h1 : L.aggregate = d) (h2 : L.count = n) (h3 : L.parts = C)
    (h4 : L.votes = v) (h5 : L.answered = s) : (⟨d, n, C, v, s⟩ : Closure P D S) = L := by
  cases L
  cases h1
  cases h2
  cases h3
  cases h4
  cases h5
  rfl

-- The votes of one part: the largest count a ballot casts for it, from nought (:357-371).
-- `if v ≤ x then x else v` is Ord::max.
def bump (v : Nat) : Option Nat → Nat
  | some x => if v ≤ x then x else v
  | none => v

def maxFold {β : Type} (f : β → Option Nat) : Nat → List β → Nat
  | v, [] => v
  | v, b :: l => maxFold f (bump v (f b)) l

-- v(p) of section 2: no count cast exceeds it, and it is nought or a count cast.
def IsLargest {β : Type} (f : β → Option Nat) (l : List β) (v : Nat) : Prop :=
  (∀ b, b ∈ l → ∀ x, f b = some x → x ≤ v) ∧ (v = 0 ∨ ∃ b, b ∈ l ∧ f b = some v)

theorem maxFold_spec {β : Type} (f : β → Option Nat) : ∀ (l : List β) (v : Nat),
    v ≤ maxFold f v l ∧ (∀ b, b ∈ l → ∀ x, f b = some x → x ≤ maxFold f v l) ∧
    (maxFold f v l = v ∨ ∃ b, b ∈ l ∧ f b = some (maxFold f v l))
  | [], v => ⟨Nat.le_refl v, fun _ h ↦ by simp at h, Or.inl rfl⟩
  | b :: l, v => by
    have e : maxFold f v (b :: l) = maxFold f (bump v (f b)) l := rfl
    rw [e]
    cases hc : f b with
    | none =>
      obtain ⟨h1, h2, h3⟩ := maxFold_spec f l (bump v none)
      refine ⟨h1, ?_, ?_⟩
      · intro b' hb' y hy
        rcases List.mem_cons.mp hb' with eb | hb'
        · rw [eb, hc] at hy
          cases hy
        · exact h2 b' hb' y hy
      · rcases h3 with h | ⟨b', hb', hy⟩
        · exact Or.inl h
        · exact Or.inr ⟨b', List.mem_cons_of_mem _ hb', hy⟩
    | some x =>
      obtain ⟨h1, h2, h3⟩ := maxFold_spec f l (bump v (some x))
      have hb1 : v ≤ bump v (some x) := by
        show v ≤ (if v ≤ x then x else v)
        by_cases hx : v ≤ x
        · rw [ite_yes hx]
          exact hx
        · rw [ite_no hx]
          exact Nat.le_refl v
      have hb2 : x ≤ bump v (some x) := by
        show x ≤ (if v ≤ x then x else v)
        by_cases hx : v ≤ x
        · rw [ite_yes hx]
          exact Nat.le_refl x
        · rw [ite_no hx]
          omega
      have hb3 : bump v (some x) = v ∨ bump v (some x) = x := by
        show (if v ≤ x then x else v) = v ∨ (if v ≤ x then x else v) = x
        by_cases hx : v ≤ x
        · rw [ite_yes hx]
          exact Or.inr rfl
        · rw [ite_no hx]
          exact Or.inl rfl
      refine ⟨Nat.le_trans hb1 h1, ?_, ?_⟩
      · intro b' hb' y hy
        rcases List.mem_cons.mp hb' with eb | hb'
        · rw [eb, hc] at hy
          cases hy
          exact Nat.le_trans hb2 h1
        · exact h2 b' hb' y hy
      · rcases h3 with h | ⟨b', hb', hy⟩
        · rcases hb3 with ex | ex
          · exact Or.inl (h.trans ex)
          · exact Or.inr ⟨b, List.mem_cons_self .., by rw [hc, h, ex]⟩
        · exact Or.inr ⟨b', List.mem_cons_of_mem _ hb', hy⟩

theorem largest_unique {β : Type} (f : β → Option Nat) (l : List β) (v w : Nat)
    (hv : IsLargest f l v) (hw : IsLargest f l w) : v = w := by
  obtain ⟨bv, ev⟩ := hv
  obtain ⟨bw, ew⟩ := hw
  rcases ev with h | ⟨b, hb, hx⟩
  · rcases ew with h' | ⟨b', hb', hx'⟩
    · omega
    · have := bv b' hb' w hx'
      omega
  · have h1 := bw b hb v hx
    rcases ew with h' | ⟨b', hb', hx'⟩
    · omega
    · have := bv b' hb' w hx'
      omega

section Certificate

variable {A P K N Rd G Bl D S : Type} [DecidableEq P] [DecidableEq K] [DecidableEq N]

def flat {X : Type} (f : Rd → List X) : List Rd → List X
  | [] => []
  | r :: rs => f r ++ flat f rs

def allOk (b : Rd → Bool) : List Rd → Bool
  | [] => true
  | r :: rs => b r && allOk b rs

theorem allOk_iff (b : Rd → Bool) : ∀ rs : List Rd, allOk b rs = true ↔ ∀ r, r ∈ rs → b r = true
  | [] => by simp [allOk]
  | r :: rs => by simp [allOk, allOk_iff b rs]

def mem (l : List P) (p : P) : Bool :=
  match l with
  | [] => false
  | q :: l => decide (q = p) || mem l p

-- The heavy loop over the rounds (:277-318): a round whose beacon is of another window, or has no
-- identifier, is refused before its attestations are read.
def rounds (R : Reads A P K) (beacon : Rd → Bool) (heavy : Rd → List A) :
    List (P × K) → List Rd → Option (List (P × K))
  | m, [] => some m
  | m, r :: rs =>
    need (beacon r = true) (seq (run R m (heavy r)) (fun m' ↦ rounds R beacon heavy m' rs))

-- The rounds refuse iff a beacon does or the loop over all their attestations does.
theorem rounds_eq (R : Reads A P K) (beacon : Rd → Bool) (heavy : Rd → List A) :
    ∀ (rs : List Rd) (m : List (P × K)),
      rounds R beacon heavy m rs = need (allOk beacon rs = true) (run R m (flat heavy rs))
  | [], m => by simp [rounds, need, allOk, flat, run]
  | r :: rs, m => by
    show need (beacon r = true) (seq (run R m (heavy r)) (fun m' ↦ rounds R beacon heavy m' rs)) =
      need ((beacon r && allOk beacon rs) = true) (run R m (heavy r ++ flat heavy rs))
    rw [run_append]
    have e : (fun m' ↦ rounds R beacon heavy m' rs) =
        fun m' ↦ need (allOk beacon rs = true) (run R m' (flat heavy rs)) :=
      funext fun m' ↦ rounds_eq R beacon heavy rs m'
    rw [e]
    cases beacon r <;> cases allOk beacon rs <;> cases run R m (heavy r) <;> rfl

def attsOf (c : Cert A P K N Rd G Bl D S) (rs : List Rd) : List A := flat c.heavy rs

def cementOf (c : Cert A P K N Rd G Bl D S) (rs : List Rd) : List P :=
  c.cement ((attsOf c rs).map c.att.part)

def ballotsOf (c : Cert A P K N Rd G Bl D S) (rs : List Rd) : List Bl := flat c.ballots rs

-- What a ballot casts for part p (:360-367): its count where it is a ballot of p, p stands in the
-- cement, and it verifies; nothing otherwise.
def castOf (c : Cert A P K N Rd G Bl D S) (rs : List Rd) (p : P) (b : Bl) : Option Nat :=
  if c.ballotPart b = p ∧ mem (cementOf c rs) p = true then c.cast b else none

def tallyOf (c : Cert A P K N Rd G Bl D S) (rs : List Rd) (p : P) : Nat :=
  maxFold (castOf c rs p) 0 (ballotsOf c rs)

omit [DecidableEq K] [DecidableEq N] in
theorem tally_spec (c : Cert A P K N Rd G Bl D S) (rs : List Rd) (p : P) :
    IsLargest (castOf c rs p) (ballotsOf c rs) (tallyOf c rs p) := by
  obtain ⟨_, h2, h3⟩ := maxFold_spec (castOf c rs p) (ballotsOf c rs) 0
  exact ⟨h2, h3⟩

-- A pledge the book counts (:377-384): its part has a key, and it verifies under that key.
def counted (c : Cert A P K N Rd G Bl D S) (key : P → Option K) (g : G) : Bool :=
  match key (c.pledgePart g) with
  | some k => c.pledgeOk g k
  | none => false

-- verify_certificate, in the order of the code. The light attestations and the ballots refuse
-- nothing (:319-371), so they enter only as what they compute.
def verify (c : Cert A P K N Rd G Bl D S) (rs : List Rd) (gs : List G) :
    Option (Closure P D S) :=
  need (c.follows = true) <|
  seq c.previous fun prev ↦
  need (c.threshold = true) <|
  seq (rounds c.att c.beacon c.heavy [] rs) fun keys ↦
  need (reachesShare (cementOf c rs).length c.count = some true) <|
  seq (checkedSum 0 ((cementOf c rs).map (tallyOf c rs))) fun s ↦
  need (votesReached s c.expected = some true) <|
  need (c.decides rs (gs.filter (counted c (lookup keys))) (tallyOf c rs) = some prev) <|
  seq (c.aggregate (cementOf c rs)) fun d ↦
  some ⟨d, (cementOf c rs).length, cementOf c rs, tallyOf c rs, c.answered rs (lookup keys)⟩

-- Pi_cert of section 2 with its closure L, clause by clause.
def PiCert (c : Cert A P K N Rd G Bl D S) (rs : List Rd) (gs : List G) (L : Closure P D S) :
    Prop :=
  ∃ prev d,
    -- (C1)
    c.follows = true ∧
    -- (C2): theta is defined, every beacon is of W, every attestation is valid in its round
    c.threshold = true ∧ (∀ r, r ∈ rs → c.beacon r = true) ∧
      (∀ a, a ∈ attsOf c rs → c.att.valid a = true) ∧
    -- (C3)
    OneKey c.att (attsOf c rs) ∧
    -- (C4)
    quorumQ (cementOf c rs).length c.count ∧
    -- (C5): v(p) the largest count cast for p, its sum below 2^64 and V(sum, K)
    (∀ p, IsLargest (castOf c rs p) (ballotsOf c rs) (L.votes p)) ∧
      total ((cementOf c rs).map L.votes) + 1 ≤ W64 ∧
      67 * c.expected ≤ 100 * total ((cementOf c rs).map L.votes) ∧
    -- (C6): id(P) exists and the book decides it, its pledges those whose part has a key
    -- and which verify under it
    c.previous = some prev ∧
      c.decides rs (gs.filter (counted c (kappa c.att (attsOf c rs)))) L.votes = some prev ∧
    -- (C7)
    c.aggregate (cementOf c rs) = some d ∧
    -- the closure
    L.aggregate = d ∧ L.count = (cementOf c rs).length ∧ L.parts = cementOf c rs ∧
      L.answered = c.answered rs (kappa c.att (attsOf c rs))

-- Lemma 2: verify_certificate returns Ok(L) iff Pi_cert holds with closure L. T and K are u64.
theorem lemma2 (c : Cert A P K N Rd G Bl D S) (rs : List Rd) (gs : List G) (L : Closure P D S)
    (hT : c.count + 1 ≤ W64) (hK : c.expected + 1 ≤ W64) :
    verify c rs gs = some L ↔ PiCert c rs gs L := by
  have h0 : 0 + 1 ≤ W64 := by
    unfold W64
    omega
  constructor
  · intro h
    simp only [verify, need_some, seq_some] at h
    obtain ⟨hf, prev, hp, ht, keys, hr, hq, s, hs, hv, hd, d, ha, hL⟩ := h
    rw [rounds_eq, need_some] at hr
    obtain ⟨hall, hrun⟩ := hr
    have hrun' : run c.att [] (attsOf c rs) = some keys := hrun
    have hlk : lookup keys = kappa c.att (attsOf c rs) :=
      funext fun p ↦ run_lookup c.att (attsOf c rs) [] keys hrun' p
    obtain ⟨hval, hone⟩ := (lemma1 c.att (attsOf c rs)).mp
      (by rw [hrun']; exact fun e ↦ nomatch e)
    obtain ⟨hs1, hs2⟩ := (checkedSum_exact _ 0 h0 s).mp hs
    subst hs1
    have hsum : total ((cementOf c rs).map (tallyOf c rs)) + 1 ≤ W64 := by omega
    have hcomm : 67 * c.expected ≤ 100 * total ((cementOf c rs).map (tallyOf c rs)) := by
      have := (votesReached_exact _ _ (by omega) hK).mp hv
      omega
    have hd' : c.decides rs (gs.filter (counted c (kappa c.att (attsOf c rs)))) (tallyOf c rs) =
        some prev := by
      rw [← hlk]
      exact hd
    have hans : c.answered rs (lookup keys) = c.answered rs (kappa c.att (attsOf c rs)) := by
      rw [hlk]
    have e := Option.some.inj hL
    subst e
    exact ⟨prev, d, hf, ht, (allOk_iff c.beacon rs).mp hall, hval, hone,
      (reachesShare_exact _ _ hT).mp hq, fun p ↦ tally_spec c rs p, hsum, hcomm, hp, hd', ha,
      rfl, rfl, rfl, hans⟩
  · rintro ⟨prev, d, hf, ht, hall, hval, hone, hq, hvotes, hsum, hcomm, hp, hd, ha, h1, h2, h3, h4⟩
    obtain ⟨keys, hrun⟩ : ∃ keys, run c.att [] (attsOf c rs) = some keys := by
      cases e : run c.att [] (attsOf c rs) with
      | none => exact absurd e ((lemma1 c.att (attsOf c rs)).mpr ⟨hval, hone⟩)
      | some k => exact ⟨k, rfl⟩
    have hlk : lookup keys = kappa c.att (attsOf c rs) :=
      funext fun p ↦ run_lookup c.att (attsOf c rs) [] keys hrun p
    have hv : L.votes = tallyOf c rs :=
      funext fun p ↦ largest_unique _ _ _ _ (hvotes p) (tally_spec c rs p)
    rw [hv] at hsum hcomm hd
    simp only [verify, need_some, seq_some]
    refine ⟨hf, prev, hp, ht, keys, ?_, (reachesShare_exact _ _ hT).mpr hq,
      total ((cementOf c rs).map (tallyOf c rs)), ?_, ?_, ?_, d, ha, ?_⟩
    · rw [rounds_eq, need_some]
      exact ⟨(allOk_iff c.beacon rs).mpr hall, hrun⟩
    · exact (checkedSum_exact _ 0 h0 _).mpr ⟨by omega, by omega⟩
    · exact (votesReached_exact _ _ hsum hK).mpr hcomm
    · rw [hlk]
      exact hd
    · exact congrArg some (Closure.eq_of L _ _ _ _ _ h1 h2 h3 hv (by rw [hlk]; exact h4))

end Certificate

end Window
