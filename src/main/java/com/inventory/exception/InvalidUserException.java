package com.inventory.exception;

/** Thrown when login fails (unknown user or wrong password) or a user is otherwise invalid. */
public class InvalidUserException extends Exception {

    public InvalidUserException(String message) {
        super(message);
    }
}
