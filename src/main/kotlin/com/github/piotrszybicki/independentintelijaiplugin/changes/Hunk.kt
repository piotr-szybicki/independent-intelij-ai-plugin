package com.github.piotrszybicki.independentintelijaiplugin.changes

/**
 * One contiguous difference between a file's session baseline and its current text.
 *
 * Line numbers are zero-based and half-open. `old*` indexes into the baseline, `new*` into the
 * document as it stands. An insertion has `oldStart == oldEnd`; a pure deletion has
 * `newStart == newEnd`.
 */
data class Hunk(
    val oldStart: Int,
    val oldEnd: Int,
    val newStart: Int,
    val newEnd: Int,
)
