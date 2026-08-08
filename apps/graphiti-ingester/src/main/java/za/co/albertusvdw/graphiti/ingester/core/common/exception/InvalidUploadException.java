package za.co.albertusvdw.graphiti.ingester.core.common.exception;

/** Raised when an upload is rejected before anything is written to disk. */
public class InvalidUploadException extends RuntimeException {

    public InvalidUploadException(String message) {
        super(message);
    }
}
