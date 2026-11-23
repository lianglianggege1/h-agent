ALTER TABLE users ADD COLUMN IF NOT EXISTS matrix_room_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_users_matrix_room_id ON users(matrix_room_id);