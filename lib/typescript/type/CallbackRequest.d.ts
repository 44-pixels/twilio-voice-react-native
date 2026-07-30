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
