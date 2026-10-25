
-- Lemma 1 (WINDOW-THEOREM.md section 4, order): the loop of verify_certificate over the
-- attestations (certificate.rs:277-318) answers Ok iff every attestation is valid in its round
-- (C2) and any two of one part carry one key (C3), so its answer does not depend on the order
-- or the repetition of the attestations handed in; and the pledged name a part keeps in attempt
-- zero, the least (place, name) of the part (agreement.rs, `Book::heavy`), is the least element
-- of a total order, which does not depend on that order either.

namespace Window

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

end Window
