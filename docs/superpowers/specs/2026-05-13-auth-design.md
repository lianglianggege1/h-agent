# 用户登录注册前后端模块设计（内部研发）

## 1. 目标与边界

### 1.1 目标
实现最小可用认证能力，支持：

- 邮箱注册（注册填写 `email + password`）
- 邮箱 + 密码登录
- 登录成功返回可解析 `user_id` 的 JWT（仅 access token）
- 后端基于 Spring Security + JWT 做鉴权
- 支持移动端 H5 风格前端接入

### 1.2 本期边界

- 不做邮箱验证码
- 不做 refresh token
- 不做第三方登录
- 不做忘记密码流程

### 1.3 成功标准

- 完成“注册 → 登录 → 访问受保护接口”的闭环
- JWT 能稳定解析 `user_id`
- 数据模型包含用户和角色（ADMIN / USER）

---

## 2. 架构与模块划分

### 2.1 后端模块（Spring Boot + Spring Security + JWT）

1. **AuthController**
   - `POST /api/auth/register`
   - `POST /api/auth/login`
   - 负责参数接收、返回标准响应

2. **AuthService**
   - 注册：校验邮箱唯一性、密码加密、创建用户、绑定默认角色
   - 登录：校验邮箱/密码、签发 JWT

3. **用户模型层**
   - `User`：用户主体信息
   - `UserRole`：用户角色映射
   - `UserRepository` / `UserRoleRepository`

4. **Security 层**
   - `JwtTokenProvider`：生成与解析 token（包含 `user_id`）
   - `JwtAuthenticationFilter`：从 `Authorization` 头提取 Bearer Token 并写入认证上下文
   - `SecurityConfig`：放行注册/登录接口，其余接口默认鉴权

### 2.2 前端模块（H5）

1. **页面层**
   - `RegisterPage`：邮箱、密码、确认密码
   - `LoginPage`：邮箱、密码

2. **API 层**
   - `register(payload)`
   - `login(payload)`
   - 统一错误码与错误提示映射

3. **会话层**
   - 登录成功后保存 `accessToken`
   - 请求拦截器自动附加 `Authorization: Bearer <token>`

### 2.3 模块边界原则

- Controller 不承载业务规则
- Service 不感知 HTTP 细节
- JWT 逻辑集中在 Security 模块
- 前端 UI / API / Token 存储分层，避免耦合

---

## 3. 接口契约

### 3.1 注册

`POST /api/auth/register`

请求：

```json
{
  "email": "user@example.com",
  "password": "P@ssw0rd123"
}
```

校验：

- `email`：必填，邮箱格式，且唯一
- `password`：必填，长度 8~64

成功响应：

```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "userId": 10001,
    "email": "user@example.com",
    "role": "USER"
  }
}
```

错误码：

- `40001` 参数错误（邮箱格式/密码不合法）
- `40002` 邮箱已注册

### 3.2 登录

`POST /api/auth/login`

请求：

```json
{
  "email": "user@example.com",
  "password": "P@ssw0rd123"
}
```

成功响应：

```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "accessToken": "<jwt>",
    "tokenType": "Bearer",
    "expiresIn": 7200,
    "user": {
      "userId": 10001,
      "email": "user@example.com",
      "role": "USER"
    }
  }
}
```

JWT Claims：

- `sub`：邮箱
- `user_id`：用户 ID
- `role`：角色
- `iat` / `exp`：签发时间与过期时间

错误码：

- `40101` 账号或密码错误
- `40102` 账号被禁用（预留）

### 3.3 鉴权约定

- Header：`Authorization: Bearer <accessToken>`
- 放行：`/api/auth/register`、`/api/auth/login`
- 其余接口默认鉴权
- token 非法或过期统一返回：

```json
{
  "code": 40100,
  "message": "Unauthorized",
  "data": null
}
```

### 3.4 统一响应结构

```json
{
  "code": 0,
  "message": "OK",
  "data": {}
}
```

- `code=0` 为成功，非 0 为业务/鉴权错误

---

## 4. 数据模型与表设计（PostgreSQL）

### 4.1 `users`

- `id` BIGSERIAL PRIMARY KEY
- `email` VARCHAR(128) NOT NULL UNIQUE
- `password_hash` VARCHAR(255) NOT NULL
- `status` SMALLINT NOT NULL DEFAULT 1（1 启用，0 禁用）
- `created_at` TIMESTAMP NOT NULL DEFAULT NOW()
- `updated_at` TIMESTAMP NOT NULL DEFAULT NOW()

索引建议：

- 唯一索引：`uk_users_email(email)`
- 普通索引：`idx_users_status(status)`

### 4.2 `user_roles`

- `id` BIGSERIAL PRIMARY KEY
- `user_id` BIGINT NOT NULL
- `role_code` VARCHAR(32) NOT NULL（`ADMIN` / `USER`）
- `created_at` TIMESTAMP NOT NULL DEFAULT NOW()

约束建议：

- 外键：`fk_user_roles_user_id -> users(id)`
- 唯一约束：`uk_user_roles_user_role(user_id, role_code)`

### 4.3 注册写入策略

注册成功后同一事务内：

1. 写入 `users`
2. 写入默认角色 `USER` 到 `user_roles`

任一步失败即事务回滚。

### 4.4 密码处理策略

- 存储 `password_hash`，不存明文
- 使用 `BCryptPasswordEncoder`
- 登录时通过 `matches(raw, hash)` 校验

### 4.5 自动建表策略

使用 **Flyway** 管理数据库迁移：

- 迁移脚本统一放在 `resources/db/migration`
- 开发/测试/生产按版本顺序自动执行
- JPA `ddl-auto` 使用 `validate`，避免生产环境隐式改表

---

## 5. 关键流程、错误处理与测试策略

### 5.1 注册流程

1. 前端提交 `email + password`
2. 后端参数校验
3. 校验邮箱唯一性
4. 密码加密
5. 写入 `users` + `user_roles(USER)`
6. 返回注册成功信息

### 5.2 登录流程

1. 前端提交 `email + password`
2. 后端查询用户并校验密码
3. 校验账号状态（启用/禁用）
4. 签发 JWT（包含 `user_id`）
5. 返回 token 与基础用户信息

### 5.3 受保护接口访问流程

1. 前端附带 Bearer Token 请求
2. JWT 过滤器解析 token
3. 解析成功写入 SecurityContext
4. 接口按鉴权规则放行/拒绝

### 5.4 错误处理规范

- 认证失败统一输出标准 JSON
- 区分参数错误、业务冲突、鉴权失败三类错误码
- 错误信息可读但不泄露内部实现（如不返回具体哈希策略）

### 5.5 测试策略

后端：

- 单元测试：
  - 邮箱格式校验
  - 密码加密与匹配
  - JWT 生成与解析（含 `user_id` claim）
- 集成测试：
  - 注册成功/邮箱重复
  - 登录成功/密码错误
  - 未携带 token 访问受保护接口被拒绝

前端：

- 页面交互：必填校验、密码确认一致性
- API 交互：注册/登录成功与失败提示
- 会话：token 存储与请求头注入

---

## 6. 非目标与后续迭代

本设计不覆盖：

- 邮箱验证码校验
- 刷新令牌与无感续期
- 忘记密码
- 多端登录管理与踢下线

后续可按需求迭代加入。
