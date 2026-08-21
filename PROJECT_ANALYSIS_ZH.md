# OpenMMO (MonMMO-EX) 项目中文分析报告

## 1. 项目概述

**OpenMMO** 是一个开源的 PokeMMO 服务端实现。该项目旨在提供一个免费且开源的 PokeMMO 服务端替代方案（目前处于开发阶段，原作者声明这纯粹是为了 MonMMO 而做出的修改）。

项目采用了现代的构建系统（Gradle）和编程语言（主要为 Kotlin），并使用了容器化技术（Docker）来简化部署和运行。

## 2. 核心模块与架构

基于项目的目录结构和文件内容，项目主要分为以下几个核心模块：

- **`common` / `common.test`**: 提供整个项目各个模块通用的基础工具类、数据结构以及测试辅助工具。
- **`network`**: 网络通信底层模块。封装了基于 Netty 的网络连接处理、字节缓冲（`NettyBuffers.kt`）、数据包编解码（校验和、加密、压缩机制）、以及网络管道和会话管理。
- **`protocols.login` / `protocols.game`**: 协议定义模块。分别负责登录服务和游戏服务的网络协议定义和数据包结构的规范。
- **`server.login`**: 登录服务器模块。
  - 负责处理客户端的登录请求和用户身份验证。
  - 使用 Jooq 进行数据库交互（管理用户信息和 Token）。
  - 提供游戏服务器的目录分发，将合法的客户端重定向到对应的游戏服。
- **`server.game`**: 游戏服务器模块（代码量最大、逻辑最复杂）。
  - 包含了游戏内的战斗、移动、地图加载、NPC交互、商店、社交（公会、好友）等核心业务逻辑。
  - 在 `server.game/src/main/kotlin/de/fiereu/openmmo/server/game/script/generated` 目录下生成了大量的地图脚本（如关东地区和丰缘地区各城镇、建筑的代码）。
  - 包含了世界状态管理、实体同步（`InterestManager`）和状态存储（基于 Jooq）。
- **`codegen`**: 代码生成模块。在编译时读取反编译项目（`decomp/` 子模块）的数据并生成游戏所需的数据和脚本。
- **`bytecodec`**: 可能是用于对基础数据类型或特定二进制结构进行编解码的工具库。
- **`launcher` / `keys`**: `launcher` 可能是用于启动服务的引导程序，`keys` 用于管理加密通信所需的密钥（例如登录服务器和游戏服务器共享的 RSA 私钥/公钥）。

## 3. 构建与运行机制

- **游戏数据生成**: 游戏数据在编译时（build time）通过 `codegen` 模块从 `pret` 反编译项目（作为 Git 子模块引入）生成。必须克隆子模块，否则由于缺少反编译数据会导致 `:codegen` 失败（`decomp/pokeblack` 是唯一的例外，它直接包含了文件）。
- **ROM 解析**: `codegen` 模块还需要 GBA ROM（如绿宝石 `BPEE` 和 火红 `BPRE`，需放置在 `roms/` 目录下）来解析对话 ID，因为反编译代码本身不包含基于零售 ROM 的硬编码偏移量。
- **依赖与部署**:
  - 项目提供 `.env.example`，所有本地配置和密钥都存放在 `.env` 中。
  - 使用 `docker compose up -d` 即可启动所需的数据库容器，然后通过 `./gradlew runAll` 运行登录和游戏服务器。
- **账号与权限**: 全新的登录数据库是空的。可以通过环境变量设置初始的管理员用户名和密码，系统在启动时若发现用户表为空则会自动创建该管理员账号。

## 4. 技术栈总结

- **编程语言**: Kotlin, Java
- **构建工具**: Gradle (Kotlin DSL: `build.gradle.kts`, `settings.gradle.kts`)
- **网络框架**: Netty (用于底层 TCP/UDP 报文处理及数据包流转)
- **数据库/ORM**: Jooq, Flyway (数据库迁移在 `server.login` 和 `server.game` 的 `resources/db/migration` 下)
- **容器化部署**: Docker, Docker Compose
- **其他**: 协议编解码、加解密（AES-CTR, RSA 等）、校验和（CRC16, HMAC-SHA256）。

## 5. 本次注释与中文化计划

由于项目规模庞大（超1500个源码文件），本次工作将重点针对底层的核心网络模块（`network`）及相关的服务器引导逻辑进行中文化和代码注释添加。我们将从 `network/src/main/kotlin/de/fiereu/network/Bootstrap.kt` 和 `Pipeline.kt` 优先开始，通过详细中文解释帮助开发者更好地理解服务端的底层通讯机制。
