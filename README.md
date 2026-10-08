# ExpBottle-Folia

ExpBottle **2.1.1-4**：面向已验证 Folia 区域调度核心的经验瓶修复分支。

基于 [wilddip/ExpBottle](https://github.com/wilddip/ExpBottle) 的 `5103b648c2e89781ec78afbce0a78b6babbfdf5a`，保留上游提交历史与作者信息。原项目源自 [SpillereNO/ExpBottle](https://github.com/SpillereNO/ExpBottle)，作者包括 BrannStroom、wilddip。

[下载修复版与源码](https://github.com/heoduling/ExpBottle-Folia/releases/tag/v2.1.1-4) · [验证记录](docs/validation.md) · [构建与支持范围](BUILD-AND-SCOPE.txt)

## 功能

- `/exp get [数量|等级L|all]`：将经验装入瓶子。
- `/exp send 玩家 数量`：转移经验。
- `/exp split 数量`：拆分经验瓶。
- `/exp give 玩家 数量 [瓶数]`：管理发瓶。
- `/exp help`、`/exp reload`：帮助及配置重载。

权限、配置默认值、物品 PDC 格式保留在 `src/main/resources`；默认权限为 OP。

## 本版修复

1. 高余额拆瓶在发货前验证扣款范围，避免不扣款却生成瓶子。
2. 覆盖 `execute run` 管理命令，拒绝破坏性的 `all/*` 操作。
3. 登记存档恢复及热启用前已存在的投射物，等待在途经验结算。
4. 避免连续重载时，新实例初始化调用栈保留旧插件类加载器。
5. 无效目标、忽略策略或准备期间失去权限不再导致永久暂停接单。
6. 清除自定义帮助页对卸载实例的引用，并保留帮助内容。

## 兼容范围和热更新影响

实测环境是指定 Shiroha bundler，实际运行 `mizuki 26.2-DEV-ver/26.2@28fe077`、协议 776，搭配 **PlugManX 3.1.0-Beta.2** 和 **Temurin 25.0.4.1+1**。这里没有承诺其他核心或管理器版本兼容。

核心和 PlugManX 的 JAR 没有修改。不过 ExpBottle 会在运行时包装管理命令节点、刷新自定义帮助索引：**其他插件的 `load / enable / reload / restart` 命令也会改为调度器排队执行**；ExpBottle 启用时，破坏性的 `all/*` 批量命令会被拒绝。不能把这一实现理解为完全不影响其他热更新命令。

受支持的是单独针对 ExpBottle 的受控管理命令。任意第三方直接禁用 API、依赖级联、插件缺席时恢复未加载区块、强杀/断电及全部第三方插件组合不在已验证保证内。

## 安装

1. 首次从旧版升级时正常停服，备份旧 JAR 和插件数据。
2. 将旧 ExpBottle JAR 移走，仅放入本版 JAR。
3. 保留原配置、语言文件和 `pending-refunds.properties`，正常启动。

回滚也在正常停服后替换 JAR；不要单独回滚退款文件而保留较新的玩家存档。

## 构建

使用 Maven 和 JDK 21 或以上，编译目标为 Java 21；本次验证使用 JDK 25。

```sh
mvn -B -ntp clean verify
```

产物为 `target/ExpBottle-2.1.1-4.jar`，不要使用 `original-*.jar`。`build-settings.xml` 仅提供已有 Maven 缓存仓库 ID，不含凭据；普通构建无需指定它。

本地最终验证：**21 项单元测试、16 项修复/生命周期场景、46 项核心功能断言、8 项真实协议客户端集成场景**。这些是已执行的隔离服结果，不是 GitHub Actions 或生产服测试声明。

正式 JAR 的 SHA-256：

```text
022119515f174267e9a40a41c4b11d0aded5239efb80e162dfa0b3f06d78e37f
```

本仓库发布源码和修复版插件，不包含测试服务器、私人核心二进制、世界存档或本机原始日志。上游自动发布工作流已移除，本分支不会自动发布到 Modrinth。
