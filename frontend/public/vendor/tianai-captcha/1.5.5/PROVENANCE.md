# tianaiCAPTCHA Web SDK 固定制品来源记录

- 后端对应版本：`cloud.tianai.captcha:tianai-captcha-springboot-starter:1.5.5`
- 上游仓库：https://github.com/dromara/tianai-captcha（镜像 https://gitee.com/dromara/tianai-captcha）
- 许可证：Apache License 2.0（见同目录 `LICENSE`，取自上游仓库 master 分支 `e7b9fd9` 提交）

## 制品来源

| 文件 | 来源 URL | SHA-256 |
| --- | --- | --- |
| `load.min.js` | https://minio.tianai.cloud/public/static/captcha/js/load.min.js | `a241bbc17bf06a579b05e2a4759e9f135ebf8521a738a27d1ef9db906b4291a9` |
| `tac/js/tac.min.js` | https://gitee.com/dromara/tianai-captcha/releases/download/tianai-captcha-1.5.5/tac-web.zip | `505f73c051908d7b805db458990790be3e91f792c4001cec0ea9377d7d302b55` |
| `tac/css/tac.css` | 同上 | `5d8099b9b612d94aeb0aa1942f5f902ebdb2a3f5b800534cb05491d3aa79cdc7` |
| `tac/images/*` | 同上 | 随 tac-web.zip 附件原样分发 |

下载日期：2026-09-05。制品固定在本目录（版本号 1.5.5），运行时仅从同源 `/vendor/tianai-captcha/1.5.5/` 加载，
不从作者域名（minio.tianai.cloud 等）运行时加载任何脚本或样式。

## 已知限制

滑块验证缺少完整键盘替代操作能力（上游 SDK 未提供无障碍模式），登录/注册对无法使用鼠标/触摸的用户暂无替代通道，
计划后续以独立邮件验证码模块补齐（见设计文档 `docs/superpowers/specs/2026-09-05-auth-slider-captcha-design.md` §9.4）。
