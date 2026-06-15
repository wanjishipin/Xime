# 贡献指南

感谢你考虑为 Xime 输入法贡献代码！请花一点时间阅读以下指南。

## 贡献流程

1. **先提 Issue，后提 PR**
   - 所有功能新增、修改或重构，**必须先创建一个 Issue**，详细说明你要做什么、为什么做以及实现思路
   - Bug 修复也建议先提 Issue 说明问题，避免重复工作
   - Issue 通过讨论达成共识后，再开始编码

2. **最小修改原则**
   - PR 应只包含实现该功能或修复该 Bug **所需的最小改动**
   - 不要在同一个 PR 中混合多个不相关的改动
   - 不要"顺便"重构无关代码或修改代码风格
   - 严格遵守 [AGENTS.md](AGENTS.md) 中的工作规则

3. **Commit 签名**
   - 所有提交必须经过 **GPG 签名**
   - 确保 Git 配置了签名密钥：
     ```bash
     git config --global user.signingkey <你的密钥ID>
     git config --global commit.gpgsign true
     ```
   - 未签名的 commit 将被拒绝

## 编码规范

- 遵循项目现有代码风格
- Kotlin 代码遵循 [Kotlin 官方编码规范](https://kotlinlang.org/docs/coding-conventions.html)
- Jetpack Compose 代码遵循 Compose 最佳实践
- 当觉得有必要时，添加单元测试

## PR 提交检查清单

- [ ] 关联的 Issue 已创建并讨论通过
- [ ] 改动符合最小修改原则
- [ ] 所有 commit 均已 GPG 签名
- [ ] 本地构建通过：`./gradlew assembleDebug --quiet`
- [ ] 测试通过：`./gradlew test`
- [ ] 已更新相关文档（如有需要）

## 提交信息格式

```
<类型>(<范围>): <描述>

[可选的详细说明]
```

类型参考：
- `feat` — 新功能
- `fix` — Bug 修复
- `refactor` — 重构
- `docs` — 文档
- `test` — 测试
- `chore` — 构建/工具链

---

再次感谢你的贡献！
