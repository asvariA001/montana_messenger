
-- Lemma 2 (WINDOW-THEOREM.md section 4, exactness), its arithmetic: every product the quorum and
-- the committee rules take in u128 (close.rs) has an operand below 2^64 and a factor of at most
-- 100, so u128::checked_mul never refuses and is the integer product, and the rules read in u128
-- are the rules of section 1.

namespace Window

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

theorem reachesShare_exact (c t : Nat) (hc : c + 1 ≤ W64) (ht : t + 1 ≤ W64) :
    reachesShare c t = some true ↔ quorumQ c t := by
  unfold reachesShare quorumQ
  split
  · constructor
    · intro e; cases e
    · intro ⟨h1, h2, _⟩; omega
  · rw [checkedMul_exact 100 c (by decide) hc, checkedMul_exact 67 t (by decide) ht]
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

end Window
