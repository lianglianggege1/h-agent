# XXL-Job 本地部署（Docker）

XXL-Job 3.4.1 调度中心：admin（控制台+调度）+ MySQL 8.4（数据）。XXL-Job 仅支持 MySQL，
无法复用本地 PG，故本栈自带 MySQL 容器。

```
局域网设备 / 你的 Java 应用（执行器）
   │ 注册 + 触发回调（双向）
   ▼
xxljob-admin :8080  ──compose 内网──►  xxljob-mysql :3306（不映射宿主机）
```

## 日常命令

```bash
./start.sh            # 启动（首次含建表，1~3 分钟）
./stop.sh             # 停止（容器保留）
./stop.sh --remove    # 删除容器，数据卷保留
docker compose logs admin -f --tail 100   # 看调度日志
docker compose logs mysql --tail 50       # 看数据库日志
```

- 控制台：`http://<宿主机IP>:8080/`（3.4.1 起 context path 为根路径，**没有**老版的 `/xxl-job-admin` 前缀）
- 默认账号：`admin / 123456`（**登录后立即改密码**：右上角用户名 → 修改密码）
- MySQL 默认不暴露宿主机；要用 DataGrip 看数据，取消 compose 里 `127.0.0.1:33061` 的注释后 `docker compose up -d`

## 执行器接入（Java/Spring Boot）

依赖（3.4.1 与调度中心同版本）：

```xml
<dependency>
    <groupId>com.xuxueli</groupId>
    <artifactId>xxl-job-core</artifactId>
    <version>3.4.1</version>
</dependency>
```

配置（`.env` 里有现成的 accessToken）：

```yaml
xxl:
  job:
    admin:
      addresses: http://<宿主机IP>:8080
    accessToken: <XXL_ACCESS_TOKEN 的值>
    executor:
      appname: my-app-executor        # 控制台"执行器管理"里 OnLine 机器地址以此 AppName 分组
      ip: <宿主机局域网IP>             # 见下方"局域网回调坑"
      port: 9999
      logpath: /data/applogs/xxl-job
      logretentiondays: 7
```

一个任务 handler 示例：

```java
@XxlJob("demoJobHandler")
public void demoJobHandler() {
    XxlJobHelper.log("hello xxl-job");
    XxlJobHelper.handleSuccess();
}
```

控制台侧：执行器管理 → 新增（AppName 与上面一致，自动注册）→ 任务管理 → 新增
（JobHandler 填 `demoJobHandler`，CRON 自定）→ 操作 → 启动。

## 局域网回调坑（最重要）

调度是**双向通信**：执行器注册到 admin，admin 触发任务时**主动回调**执行器注册的 `ip:port`。

- 执行器 `xxl.job.executor.ip` **必须显式填宿主机局域网 IP**
  （如 `192.168.x.x`）。留空让它自动探测，会拿到执行器进程所在机器的内网地址——
  只要执行器和 Docker 宿主机是同一台机器，探测值恰好也能通；执行器跑在**别的机器**上时
  必须填那台机器的 IP。
- 执行器机器的 9999 端口（`xxl.job.executor.port`）要能被宿主机访问（防火墙放行）。
- 调度失败日志显示"connect timeout"时，九成是回调地址不可达，而不是 admin 本身的问题。

## 内存调整

`.env` 里改，`docker compose up -d` 生效：

```bash
ADMIN_MEM_LIMIT=512m    # admin 容器上限（JVM 已配 -Xms128m -Xmx384m，改上限时同步改）
ADMIN_JAVA_OPTS=-Xms128m -Xmx384m
MYSQL_MEM_LIMIT=512m    # mysql 容器上限（已关 performance_schema、buffer pool 128M）
```

物理机吃紧时可整体降为 384m/384m（JVM 降 -Xmx256m）。

## 首次初始化的内容（init/tables_xxl_job.sql）

- 8 张表 + 默认数据：admin 账号、2 个执行器分组（通用/AI Sample）、4 个示例任务
  （demoJobHandler / ollamaJobHandler / difyWorkflowJobHandler / openClawJobHandler）
- 示例任务默认停止状态，仅作参考；AI 类 handler 需要执行器侧实现，没有对应执行器时忽略即可
- SQL 仅在 MySQL **数据卷为空时**执行一次；重跑 start.sh 不会重复建表

## 完全重置（删光数据重来）

```bash
./stop.sh --remove
docker volume rm xxljob_xxljob-mysql-data
./start.sh
```

## 排障

| 症状 | 处理 |
|------|------|
| admin 反复重启 | `docker compose logs admin` 大概率是连不上 MySQL，看 mysql 是否 healthy |
| 登录后页面空白/接口 404 | 3.4.1 控制台在根路径（`http://IP:8080/`），不要带 `/xxl-job-admin` 前缀 |
| 日志页 `/joblog/pageList` 500，报 `date must not be null` | 3.4.1 上游 bug：执行器回调丢失的日志行 `handle_time` 为 NULL，DTO 格式化时未判空。清理即可：`docker exec xxljob-mysql mysql -uroot -p<root密码> -e "USE xxl_job; DELETE FROM xxl_job_log WHERE handle_time IS NULL;"`（执行器宕机时触发的任务会产生这类行，属已知上游缺陷） |
| 执行器注册不上 | 核对 accessToken 两端一致（请求头 `XXL-JOB-ACCESS-TOKEN`，xxl-job-core 自动处理）；执行器机器能 curl 通 admin 的 8080 |
| 触发任务 connect timeout | 见上面"局域网回调坑"，检查 executor ip 与 9999 端口可达性 |
