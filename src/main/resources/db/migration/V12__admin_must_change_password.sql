-- Set when somebody else knows the password. Existing accounts stay as they are.
ALTER TABLE admin_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
