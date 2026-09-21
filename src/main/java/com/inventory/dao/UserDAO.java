package com.inventory.dao;

import com.inventory.exception.DatabaseException;
import com.inventory.model.Role;
import com.inventory.model.User;
import com.inventory.util.DBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * All SQL for the users table. Pure data access: it reports "found / not found"
 * and leaves the decision of what that means (e.g. InvalidUserException) to the service.
 */
public class UserDAO {

    /**
     * Looks up a user by username AND password in one query.
     * Passwords are stored in plain text for this learning project (see README,
     * "Future improvements": hash with BCrypt/PBKDF2). The password is never
     * loaded into the returned User.
     */
    public Optional<User> findByCredentials(String username, String password) {
        String sql = "SELECT user_id, username, role FROM users WHERE username = ? AND password = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, password);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapUser(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("Login lookup failed: " + e.getMessage(), e);
        }
    }

    public Optional<User> findById(int userId) {
        String sql = "SELECT user_id, username, role FROM users WHERE user_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapUser(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("User lookup failed: " + e.getMessage(), e);
        }
    }

    private User mapUser(ResultSet rs) throws SQLException {
        return new User(rs.getInt("user_id"), rs.getString("username"), Role.valueOf(rs.getString("role")));
    }
}
