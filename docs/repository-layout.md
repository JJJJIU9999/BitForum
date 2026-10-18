# 仓库入口与维护约定

## 唯一正式目标

产品源码及后续对外维护统一面向 **[JJJJIU9999/BitForum](https://github.com/JJJJIU9999/BitForum)**。`main`保存已发布版本，更新先在分支完成审查与验证，再发布；本次更新分支为`sync/local-accepted-20261017`，已发布到[PR #1](https://github.com/JJJJIU9999/BitForum/pull/1)，合并状态以该PR为准。

| 本地目录 | 用途 |
|---|---|
| `bit-forum-v24-public` | BitForum正式仓库对应的源码目录；名称沿用历史，不表示只能维护V24 |
| `bit-forum-spring` | 原开发仓库与未整理内部资料，保留回溯；不要从这里直接推整段历史到BitForum |
| `bit-forum-spring-release-v24` | 原历史发布worktree，保留，不作为新功能入口 |
| `local-demo` / `local-acceptance` / `local-finalization` | 本机运行、密钥与原始证据，均在产品仓库外 |

旧`Bit_Forum_Spring`仓库保留历史；本轮不删除、不归档远端、不更改其origin，也不向它发布新成果。两个仓库根历史独立，公开维护提交以BitForum已发布main为父节点，不做force push或unrelated-histories合并。

## 今后工作顺序

1. 进入BitForum对应目录，先检查分支与工作树；从正式main起维护功能分支。
2. 按清单提交产品代码、测试及公开说明；内部验收报告、真实密钥、账号、数据、日志和构建输出不进入Git。
3. Commit与Push分开授权，推送前核对目标仓库、分支、提交范围和远端变化；新CI完成前不能声称远端通过。
4. 本机demo使用既有管理守卫与仓库外冻结候选，源码更新不自动替换服务、升级数据库或调整Docker资源。

本轮保留了原README的结构与已审定内容，仅更新版本和能力边界；历史截图和V24CI记录仍明确注明时点。整理回执和原件保存在本机`local-finalization/repository-sync-20261017`，不纳入公开提交。
