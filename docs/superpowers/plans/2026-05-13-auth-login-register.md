# 登录注册功能实现计划

> **给执行型 Agent：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 按任务逐步执行。步骤使用 `- [ ]` 复选框追踪。

**目标：** 实现邮箱+密码的注册登录能力，后端基于 Spring Security + JWT（仅 access token），并提供最小可用 H5 前端接入。

**架构：** 后端采用 Controller → Service → Persistence → Security 分层；JWT 无状态并携带 `user_id` 与 `role`。数据库由 Flyway 管理迁移。前端按页面层/API 层/会话层拆分，并通过请求拦截器附加 Bearer Token。

**技术栈：** Spring Boot 4、Spring Security、MyBatis-Plus、PostgreSQL、Flyway、JUnit5/MockMvc、H5 前端框架（按现有项目）。

---

## 任务 1：依赖与基础配置

**文件：**
- 修改 `backend/pom.xml`
- 修改 `backend/src/main/resources/application.yml`

- [ ] 写失败测试：校验 `JwtTokenProvider` Bean 存在
- [ ] 运行测试确认失败
- [ ] 增加依赖：`security`、`validation`、`flyway`、`jjwt`
- [ ] 增加配置：`jwt.secret`、`jwt.expiration-seconds`、`flyway.locations`
- [ ] 重跑测试确认通过
- [ ] 提交一次 commit

---

## 任务 2：数据库迁移与持久层

**文件：**
- 新建 `backend/src/main/resources/db/migration/V1__create_users_and_roles.sql`
- 新建 `backend/src/main/java/com/h/backend/user/entity/UserEntity.java`
- 新建 `backend/src/main/java/com/h/backend/user/entity/UserRoleEntity.java`
- 新建 `backend/src/main/java/com/h/backend/user/mapper/UserMapper.java`
- 新建 `backend/src/main/java/com/h/backend/user/mapper/UserRoleMapper.java`

- [ ] 写失败测试：插入用户+角色并按邮箱查询
- [ ] 运行测试确认失败
- [ ] 编写迁移 SQL：`users`、`user_roles`、外键与唯一约束
- [ ] 实现 Entity + Mapper（MyBatis-Plus）
- [ ] 重跑测试确认通过
- [ ] 提交一次 commit

---

## 任务 3：JWT 与安全过滤链

**文件：**
- 新建 `backend/src/main/java/com/h/backend/security/JwtTokenProvider.java`
- 新建 `backend/src/main/java/com/h/backend/security/AuthUserPrincipal.java`
- 新建 `backend/src/main/java/com/h/backend/security/JwtAuthenticationFilter.java`
- 新建 `backend/src/main/java/com/h/backend/security/SecurityConfig.java`
- 新建测试 `backend/src/test/java/com/h/backend/security/JwtTokenProviderTest.java`

- [ ] 写失败测试：token 必须包含 `user_id`、`role`
- [ ] 运行测试确认失败
- [ ] 实现 `generateToken/parse/isValid`
- [ ] 配置过滤链：放行 `/api/auth/register`、`/api/auth/login`，其余鉴权
- [ ] 重跑测试确认通过
- [ ] 提交一次 commit

---

## 任务 4：注册登录服务与接口

**文件：**
- 新建 DTO：
  - `auth/dto/RegisterRequest.java`
  - `auth/dto/LoginRequest.java`
  - `auth/dto/AuthUserResponse.java`
  - `auth/dto/LoginResponse.java`
- 新建服务：
  - `auth/service/AuthService.java`
  - `auth/service/impl/AuthServiceImpl.java`
- 新建控制器：
  - `auth/controller/AuthController.java`
- 新建通用响应与异常：
  - `common/api/ApiResponse.java`
  - `common/exception/BusinessException.java`
  - `common/exception/GlobalExceptionHandler.java`
- 新建测试 `backend/src/test/java/com/h/backend/auth/AuthServiceTest.java`

- [ ] 写失败测试：注册成功、邮箱重复、登录失败、登录成功返回 JWT
- [ ] 运行测试确认失败
- [ ] 最小实现：邮箱唯一校验、BCrypt 加密、写入默认 USER 角色、登录签发 token
- [ ] 实现统一响应和错误码映射（`40001/40002/40100/40101`）
- [ ] 重跑测试确认通过
- [ ] 提交一次 commit

---

## 任务 5：接口集成测试（含受保护接口）

**文件：**
- 新建 `backend/src/test/java/com/h/backend/auth/AuthControllerIntegrationTest.java`

- [ ] 写失败测试：
  - 注册成功
  - 登录成功并返回 accessToken
  - 无 token 访问受保护接口返回 `40100`
  - 有 token 访问成功
- [ ] 运行测试确认失败
- [ ] 最小修正代码（状态码、过滤顺序、响应结构）
- [ ] 重跑测试确认通过
- [ ] 提交一次 commit

---

## 任务 6：H5 前端最小接入

**文件（按现有前端技术栈替换后缀）：**
- 新建 `frontend/src/pages/auth/RegisterPage.(vue|tsx)`
- 新建 `frontend/src/pages/auth/LoginPage.(vue|tsx)`
- 新建 `frontend/src/api/auth.(ts|js)`
- 修改 `frontend/src/api/http.(ts|js)`
- 新建 `frontend/src/store/session.(ts|js)`

- [ ] 写失败测试（若前端测试框架已存在）
- [ ] 运行测试确认失败
- [ ] 实现注册/登录页面（邮箱、密码、确认密码）
- [ ] 实现 API 与 token 存储、请求头注入
- [ ] 重跑测试或至少 build 验证通过
- [ ] 提交一次 commit

---

## 任务 7：全量验证与文档对齐

**文件：**
- 按需修改 `docs/spec/登录认证.md`
- 按需修改 `docs/sql/dml.sql`

- [ ] 跑后端全量测试：`cd backend && ./mvnw test`
- [ ] 跑前端验证命令（按项目实际命令）
- [ ] 手工冒烟：注册→登录→带 token 访问受保护接口→无 token 校验 401
- [ ] 文档与实现差异对齐
- [ ] 提交一次 commit
