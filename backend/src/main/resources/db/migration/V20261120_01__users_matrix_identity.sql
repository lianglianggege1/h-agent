ALTER TABLE users ADD COLUMN IF NOT EXISTS matrix_user_id VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS matrix_password VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_matrix_user_id ON users(matrix_user_id);