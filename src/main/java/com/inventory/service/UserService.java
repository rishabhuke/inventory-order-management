package com.inventory.service;

import com.inventory.dao.UserDAO;
import com.inventory.exception.InvalidUserException;
import com.inventory.model.Role;
import com.inventory.model.User;

/**
 * Login and role checks.
 *
 * The role guards are static helpers so every service can enforce authorisation the same way.
 * They live in the SERVICE layer (not just in the menu) so that a rule like "only admins may
 * delete products" is enforced no matter which screen or future front end calls the service.
 */
public class UserService {

    private final UserDAO userDAO;

    public UserService(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    /**
     * Validates the credentials and returns the logged-in user.
     * The error message is deliberately the same for "unknown user" and "wrong password",
     * so the login screen does not reveal which usernames exist.
     */
    public User login(String username, String password) throws InvalidUserException {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new InvalidUserException("Username and password must not be empty.");
        }
        return userDAO.findByCredentials(username.trim(), password)
                .orElseThrow(() -> new InvalidUserException("Invalid username or password."));
    }

    public static void requireAdmin(User user) throws InvalidUserException {
        requireLoggedIn(user);
        if (user.getRole() != Role.ADMIN) {
            throw new InvalidUserException("Access denied: administrator privileges required.");
        }
    }

    public static void requireCustomer(User user) throws InvalidUserException {
        requireLoggedIn(user);
        if (user.getRole() != Role.CUSTOMER) {
            throw new InvalidUserException("Access denied: only customers can do this.");
        }
    }

    private static void requireLoggedIn(User user) throws InvalidUserException {
        if (user == null) {
            throw new InvalidUserException("You must be logged in.");
        }
    }
}
