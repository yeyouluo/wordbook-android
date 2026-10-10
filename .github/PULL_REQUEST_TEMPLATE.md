## 这个 PR 做了什么

<!-- 一句话说明；如果有关联 Issue，写 "Closes #123" -->

## 为什么需要它

<!-- 用户场景比改动清单更重要：什么情况下会遇到这个问题？ -->

## 改动类型

- [ ] 新功能（feat）
- [ ] 缺陷修复（fix）
- [ ] 重构 / 性能（refactor / perf）
- [ ] 文档（docs）
- [ ] 构建 / CI（build / ci）

## 自检清单

- [ ] `./gradlew assembleDebug` 通过
- [ ] 界面改动**附了截图**（前后对比更好）
- [ ] 数据库改动写了 `Migration`，没有用 `fallbackToDestructiveMigration`
- [ ] 新的规则类逻辑放在 `util/` 并被复用，没有复制第二份
- [ ] 没有提交 `local.properties` / `keystore/` / `.gradle/` / `.kotlin/` / `app/build/`
- [ ] 若涉及用户可见行为变化，已同步更新 `README.md` 或 `使用手册.md`

## 补充说明

<!-- 兼容性影响、迁移策略、已知取舍等 -->
