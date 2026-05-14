show databses;

-- 1. 创建数据库 h_agent_db，指定编码 UTF8、排序规则
CREATE DATABASE h_agent_db;

-- 2. 创建项目专用用户
CREATE USER h_agent WITH PASSWORD 'h_agent';

GRANT ALL ON SCHEMA public TO h_agent;

GRANT USAGE, CREATE ON SCHEMA public TO h_agent;
GRANT ALL PRIVILEGES ON SCHEMA public TO h_agent;
ALTER SCHEMA public OWNER TO h_agent;

