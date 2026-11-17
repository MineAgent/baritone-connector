# MGBaritoneConnector — Minecraft Baritone 控制模组（Fabric / Minecraft 26.3，仅支持 26.3）

把 Baritone 的控制接口挂在 **MGHttpdProvider** 的共享 HTTP 服务（`127.0.0.1:3420`）的 `/bt` 前缀下：

* `GET /bt/` → 返回完整使用说明
* `POST /bt/` → 执行一条 Baritone 命令（纯文本请求体）

这是从 [mcctl](https://github.com/MineAgent/mcctl) 的 `bt` / `#` 功能拆出来的独立模组。
mcctl 从 1.6.3 起不再提供 Baritone 控制；需要 Baritone 时，同时安装本模组并调用 `/bt`。

HTTP 服务、路由和「关游戏时结束 JVM」都由 [MGHttpdProvider](https://github.com/MineAgent/HttpdProvider) 负责，
本模组只注册自己的前缀。

## 依赖

| 模组 | 是否必需 | 说明 |
| --- | --- | --- |
| [MGHttpdProvider](https://github.com/MineAgent/HttpdProvider) | **必需** | 1.2.1+，提供 `127.0.0.1:3420` 的共享 HTTP 服务和前缀路由 |
| [Baritone](https://github.com/cabaletta/baritone) | **必需** | 实测 **1.20.0**（`baritone-api-fabric-1.20.0.jar`，面向 Minecraft 26.3） |
| Fabric Loader | **必需** | 0.20.1+，不需要 Fabric API |

`fabric.mod.json` 里显式声明了 `depends: { httpdprovider, baritone }`。Baritone 没装时
Fabric 会直接拒绝加载本模组，而不是让接口在运行时静默失败。

本模组**不打包** Baritone，也不打包 MGHttpdProvider；它们的 jar 仍然放在 `.minecraft/mods/`。

## 安装

1. Minecraft **26.3** + Fabric Loader **0.20.1+**（不需要 Fabric API）。
2. 把 `httpdprovider-1.2.1.jar`、`baritone-api-fabric-1.20.0.jar`、
   `mgbaritoneconnector-1.0.1.jar` 一起放进 `.minecraft/mods/`。
3. 启动后日志里会出现：

   ```
   MGHttpdProvider listening on http://127.0.0.1:3420
   registered /bt (MGBaritoneConnector — Baritone 控制)
   ```

4. `curl http://127.0.0.1:3420/` 可以看到 `/bt` 已经挂上。

## 使用

请求体就是一条 Baritone 命令，和聊天框里输入 `#<命令>` 等价，区别是不发聊天包：

```bash
curl -X POST --data-binary 'goal ~ ~ ~20' http://127.0.0.1:3420/bt
curl -X POST --data-binary 'mine iron_ore' http://127.0.0.1:3420/bt
curl -X POST --data-binary 'stop'          http://127.0.0.1:3420/bt
```

为了从 mcctl 的旧写法迁移，`#` 和 `bt ` 前缀也接受，下面三条等价：

```bash
curl -X POST --data-binary 'stop'    http://127.0.0.1:3420/bt
curl -X POST --data-binary '#stop'   http://127.0.0.1:3420/bt
curl -X POST --data-binary 'bt stop' http://127.0.0.1:3420/bt
```

* 一次请求只执行**一条**命令；多行请求返回 400，请拆成多个请求。
* 命令输出（例如 `[Baritone] Goal: GoalBlock{...}`）仍然在聊天栏，用
  AdvancedInfoFetcher 的 `GET http://127.0.0.1:3420/aif/msg` 读取。
* 在 shell 里必须给整条命令加引号，否则 bash 会把 `~` 展开成 `$HOME`。

### 返回

| 状态码 | 含义 |
| --- | --- |
| 200 | `ok: <规范化后的命令>` |
| 400 | 空命令 / 多行 / 未进世界 / 没有 primary Baritone / 未知命令 / Baritone API 调用失败 |
| 404 | `/bt` 下没有这个路径 |
| 405 | 方法不允许（`Allow: GET, HEAD, POST, OPTIONS`） |
| 413 | 请求体过大（>16KB） |
| 500 | 内部错误 |

失败时正文会给出具体原因，例如：

```
Baritone command failed: no world loaded (join a world first)
Baritone command failed: unknown Baritone command: nope
```

## 命令行工具

仓库里带了薄封装脚本 `mgbt`（内部就是 curl）：

```bash
./mgbt 'goal ~ ~ ~20'
./mgbt 'mine iron_ore'
./mgbt stop
./mgbt '#stop'
./mgbt help                     # 完整说明 (GET /bt/)
MGBT_URL=http://127.0.0.1:3420/bt ./mgbt stop
```

## 实现

```text
BaritoneEndpoint          /bt 的 HTTP 路由、请求体归一化、状态码
BaritoneCommandExecutor   执行抽象（HTTP 层不依赖 Minecraft/Baritone，便于离线测试）
BaritoneExecutor         直接调用 Baritone API，切到客户端线程执行
Help                      GET /bt/ 的完整说明
MGBaritoneConnectorMod    Fabric 客户端入口，把 /bt 注册到 MGHttpdProvider
```

执行链：

```java
BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute(command)
```

这正是聊天框里 `#goal ~ ~ ~20` 内部走的路径，只是省掉了聊天包。

线程模型：

```text
HTTP 工作线程 → BaritoneExecutor 的锁（命令串行）→ Minecraft.execute()
             → 渲染线程调用 Baritone API → HTTP 线程等结果（最多 10 秒）
```

并发请求由锁串行化，不会交叉执行。`HEAD /bt/` 只返回状态和类型，不执行命令。

## 构建

需要 **JDK 25**（Minecraft 26.3 要求）。Minecraft 26.1 起官方代码不再混淆，
所以 Loom 不需要任何 mappings 配置。

```bash
./gradlew build          # 产物: build/libs/mgbaritoneconnector-1.0.1.jar
```

Baritone 的 API jar 是 **compileOnly**，不会被打进本模组的 jar。首次构建时
`downloadBaritone` 任务会从 Baritone 的 GitHub release 下载固定版本到
`build/deps/baritone-api-fabric-1.20.0.jar`，并校验 SHA-256：

```
49adfc063cfbfd0b6f08e9d814359807baa2d1768c0d39d6c5968268547cbca6
```

如果构建环境不能联网，手动把 `baritone-api-fabric-1.20.0.jar` 放到
`build/deps/` 即可；文件不存在或校验失败时构建会明确报错。

## 不依赖游戏验证 HTTP 层

`tools/VerifyServer.java` 只编译不碰 Minecraft/Baritone 的类，用假执行器挂载 `/bt`，
自动检查说明、命令归一化、失败状态码、404/405/413，以及 MGHttpdProvider 的 `GET /` 索引：

```bash
LOG4J_API=~/.minecraft/libraries/org/apache/logging/log4j/log4j-api/2.26.0/log4j-api-2.26.0.jar
mkdir -p build/verify
javac --release 25 -encoding UTF-8 -d build/verify \
  -cp libs/httpdprovider-1.2.1.jar \
  src/main/java/com/example/mgbc/{BaritoneCommandExecutor,BaritoneEndpoint,Help}.java \
  tools/VerifyServer.java
java -cp "build/verify:libs/httpdprovider-1.2.1.jar:$LOG4J_API" VerifyServer
```

MGHttpdProvider 1.2.1 用游戏自带的 log4j2 记日志，脱离游戏运行时要把 log4j-api 放进
classpath；没有 log4j-core 时会在 stderr 打一行「could not find a logging provider」，
不影响 HTTP 测试结果。全部通过时退出码为 0。加 `--serve` 可以让它停在 3420，
方便再用 curl 手工验证。

## 验证情况

**已在真实客户端跑通**（Minecraft 26.3 + Fabric Loader 0.20.1 + Baritone 1.20.0 +
MGHttpdProvider 1.2.1，`--quickPlaySingleplayer test` 直接进存档；Xvfb 无头启动，
`SDL_VIDEO_X11_FORCE_EGL=1` + `LIBGL_ALWAYS_SOFTWARE=1` 走 llvmpipe）：

| 测试 | 结果 |
| --- | --- |
| 启动 | 模组列表出现 `baritone 1.20.0`、`mgbaritoneconnector 1.0.1`；日志出现 `registered /bt (MGBaritoneConnector — Baritone 控制)`，`GET /` 索引列出 `/bt` |
| `GET /bt/` | 200，说明标题为 `Minecraft 26.3`、`Baritone 1.20.0+` |
| `POST /bt/ 'goal ~ ~ ~5'` | 200 `ok: goal ~ ~ ~5`；`/aif/msg` 出现 `[Baritone] Goal: GoalBlock{x=-8,y=130,z=-30}` |
| `POST /bt/ '#stop'` | 200 `ok: stop`；`/aif/msg` 出现 `[Baritone] ok canceled` |
| `POST /bt/ 'bt stop'` | 200 `ok: stop`，`bt ` 前缀被正确去掉 |
| `POST /bt/` 空 body / 多行 | 400，正文分别说明空命令和多命令限制 |
| `GET /bt/nope` / `PUT /bt/` | 404 / 405（`Allow: GET, HEAD, POST, OPTIONS`） |
| `HEAD /bt/` / 20KB body | 200 空 body / 413 |
| `./mgbt 'goal ~ ~ ~3'`、`./mgbt stop` | 与 curl 结果一致，Baritone 输出可在 `/aif/msg` 读到 |
| 离线 `tools/VerifyServer.java` | HTTP 用例全过（说明/版本/归一化/400/404/405/413/HEAD/索引） |
| 退出 | 经暂停菜单「保存并退回到标题屏幕」→「退出游戏」，日志出现 `client exited, stopping the HTTP server`，3420 释放。Baritone 的 worker pool 是非 daemon 线程，vanilla 的 post-main 看门狗约 15 秒后写 `Client shutdown from post-main` 崩溃报告并 `System.exit(-8)`（实测客户端退出码 248）；这是 MGHttpdProvider 1.2.x 起「不替别的模组兜底」的预期行为，与本模组无关（见「已知限制」） |

## 版本

* **1.0.1** — 仅支持 Minecraft 26.3：Baritone 1.20.0、Fabric Loader 0.20.1、
  MGHttpdProvider 1.2.1；`fabric.mod.json` 的 `minecraft` 约束为 `~26.3`。

## 已知限制

* 不捕获 Baritone 的输出；命令结果通过聊天栏（`/aif/msg`）或游戏画面确认。
* 一次请求只执行一条命令。
* 只封装 Baritone 的命令管理器，不额外提供状态/路径/设置查询接口。
* 只有进入世界后 Baritone 才有 primary 实例；标题界面和加载界面返回 400。
* 退出游戏时 Baritone 的非 daemon worker 线程会拖住 JVM；MGHttpdProvider 1.2.x 不再 `System.exit`，
  于是 vanilla 的 post-main 看门狗会写一份 `Client shutdown from post-main` 崩溃报告并
  `System.exit(-8)`（退出码 248）。HTTP 服务已正常停止、3420 已释放；这是 provider 有意不替别的
  模组兜底的行为，本模组不额外处理。
* 服务只绑定 `127.0.0.1`，没有鉴权；不需要时请删除模组或关闭游戏。

## 许可证

LGPL-3.0-only（GNU Lesser General Public License v3.0）：
完整文本见 [`LICENSE`](LICENSE)（LGPL-3.0），其中引用的 GPL-3.0 见 [`LICENSE.GPL-3.0`](LICENSE.GPL-3.0)；
所有源码文件头部标有 `SPDX-License-Identifier: LGPL-3.0-only`。
Baritone 本身也是 LGPL-3.0，由玩家单独安装，本项目不打包、不修改它的代码。
