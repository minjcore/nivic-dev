/**
 * Core domain boundary: wallet acceptance pipeline, WAL signing/verification, and ledger
 * invariants shared across HTTP and batch tooling. Related concrete types today live in {@code
 * dev.nivic.ledger}, {@code dev.nivic.payment}, {@code dev.nivic.wal}, and {@code dev.nivic.sevlet};
 * prefer adding new cross-cutting Core types here when they are not servlet- or transport-specific.
 */
package dev.nivic.core;
