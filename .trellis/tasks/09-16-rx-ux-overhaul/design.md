## 10. 回滚（实施后修订）

原计划按 4 个关注点分别提交（① JNI ② 发布 ③ UI ④ CI）。实际实施时发现：`MainActivity` 的收件队列、发布、状态条、模式对话框是同一批编辑的产物，拆成 3 个中间提交既无法单独编译、也不具备真实的回滚价值。

因此改为 3 个自洽提交：

1. `chore: trellis`（脚手架，与功能无关，可整体丢弃）
2. `feat: 接收可靠性 + 交互重做`（`jni.cpp` + 4 个 Java 文件 + 资源 + manifest + 版本号）——本次的实质改动，作为一个原子单元
3. `ci: GitHub Actions`（新增 workflow，可独立回滚而不影响功能）

回滚粒度：

- CI 出问题 → `git revert <ci commit>` 或直接改 workflow 文件。
- 功能出新问题 → `git checkout <feat commit>~1 -- <path>` 可只回滚单个文件（例如只回滚 `jni.cpp` + `MainActivity.java` 的 native 声明，同时保留 UI 改动）。
- 无数据迁移、无 schema 变更、无外部状态，因此不需要数据回滚脚本。
