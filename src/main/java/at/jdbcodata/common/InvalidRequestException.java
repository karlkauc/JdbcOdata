package at.jdbcodata.common;

/** Client error (HTTP 400): bad parameter value, missing required parameter, invalid definition. */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
