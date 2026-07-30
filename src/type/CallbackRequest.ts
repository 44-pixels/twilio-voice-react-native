// FORK — KAR-873
// Owns: Public callback-request payload shared by Voice and native bindings.
// Hooks into: Voice.tsx and type/NativeModule.ts.
// Re-check on SDK bump: Voice public event and native promise typings.

/**
 * A callback request initiated from the system call history.
 *
 * @public
 */
export interface CallbackRequest {
  /** A unique identifier for deduplicating callback delivery. */
  requestId: string;

  /** The application destination associated with the call-history entry. */
  handle: string;
}
