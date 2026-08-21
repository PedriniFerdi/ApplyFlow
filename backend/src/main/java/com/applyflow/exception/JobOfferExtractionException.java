package com.applyflow.exception;

public class JobOfferExtractionException extends RuntimeException {

    public JobOfferExtractionException(String message) {
        super(message);
    }

    public JobOfferExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
