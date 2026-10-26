/**
 * Shared, pure, side-effect-free financial calculation library (decision PD-21 option (a)).
 *
 * <p>Rules for this module once calculation code is added (not part of the project foundation):
 * no I/O, no clock, no randomness, no framework dependencies; exact arithmetic only; every public
 * calculation is covered by reviewed golden fixtures. Money representation and rounding policy are
 * still pending owner and external tax/accounting decisions (PD-09), so no money type exists yet.
 */
package com.finora.financecore;
