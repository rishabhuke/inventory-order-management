package com.inventory.model;

/**
 * A logged-in user. The password is deliberately NOT a field here: it is only
 * ever compared inside the database query during login and never held in memory.
 */
public class User {

    private final int userId;
    private final String username;
    private final Role role;

    public User(int userId, String username, Role role) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username must not be blank");
        }
        if (role == null) {
            throw new IllegalArgumentException("Role must not be null");
        }
        this.userId = userId;
        this.username = username;
        this.role = role;
    }

    public int getUserId()      { return userId; }
    public String getUsername() { return username; }
    public Role getRole()       { return role; }
    public boolean isAdmin()    { return role == Role.ADMIN; }

    @Override
    public String toString() {
        return "User{id=" + userId + ", username='" + username + "', role=" + role + "}";
    }
}
