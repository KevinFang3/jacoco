# JaCoCo Agent 定制扩展说明（customization）

> 本仓库（jacocoagent）在官方 JaCoCo 基础上做的一组定制扩展，用于对接公司内网
> 质量门户（`http://qa.fzzqft.com/portaljava/codeCoverage/agent`），并修复
> 老中间件（宝兰德 BES）与插桩字段带来的两个运行时问题。
>
> 设计目标：**定制代码全部集中在新增文件里，org.jacoco.core 与原 agent 文件
> 保持原样，升级 JaCoCo 源码时零冲突**（唯一接线点：`PreMain` 两行钩子调用）。

---

## 1. 功能总览

| 能力 | 说明 |
|---|---|
| 端口冲突探测 | dump 端口被占（宝兰德 BES 场景 `address in use`）提前 +1 并**上报实际端口** |
| 门户上报 | 启动后向门户异步上报 dump 端口 / jar 下载端口（`reportDumpPort` / `reportHttpPort`） |
| Jar 下载服务 | agent 内嵌 HTTP 服务，门户端拉取应用 jar |
| `$jacocoData` 反射屏蔽 | 插桩后处理：`Class.getDeclaredFields()` 调用点改写为过滤方法，剔除 jacoco 注入字段 |
| 失败安全 | 所有增强逻辑异常不外传，**绝不阻断 agent / 业务启动**（premain 抛异常 = 进程终止，必须避免） |

---

## 2. 文件清单（全部为新增文件）

生产代码（`org.jacoco.agent.rt/src/org/jacoco/agent/rt/internal/`）：

| 文件 | 职责 |
|---|---|
| `AgentEnhancer.java` | 生命周期增强钩子（唯一被 PreMain 接线调用的入口） |
| `JarDownloadServer.java` | 内嵌 HTTP 下载服务 + 端口上报 |
| `SimpleHttpUtil.java` | 门户 HTTP 客户端工具（POST 上报 / URL 常量 / body 组装） |
| `DeclaredFieldsRewriter.java` | `$jacocoData` 后处理改写 transformer + 过滤静态方法 |

测试（`org.jacoco.agent.rt.test/src/org/jacoco/agent/rt/internal/`）：

| 文件 | 覆盖 |
|---|---|
| `AgentEnhancerTest.java` | 端口冲突 +1 / 空闲不变 / 非法端口不抛异常（3 用例） |
| `DeclaredFieldsRewriterTest.java` | 调用点改写 / 穿透 / 过滤 / 无改写返回 null / 畸形字节码不抛（6 用例） |
| `SimpleHttpUtilTest.java` | 上报 body 组装、env 小写化、appName/env 缺失返回 null（4 用例） |

原文件改动：**仅 `PreMain.java` 增加 2 行钩子调用**（+5 行含注释）；其余原文件
（`TcpServerOutput` / `MethodProbesAdapter` / `MethodProbesAdapterTest`）均已恢复官方原样。

---

## 3. 启动流程与时序

```
JVM 启动（-javaagent → premain）
  ├─ AgentEnhancer.beforeStartup(agentOptions)      ← 第 1 个钩子（Agent.getInstance 之前）
  │     ├─ adjustPortOnConflict：探测 dump 端口，被占则 setPort(+1)
  │     └─ reportDumpPort：异步上报 action=reportDumpPort（含实际端口）
  ├─ Agent.getInstance(agentOptions)                 ← 官方初始化（TcpServerOutput 绑定端口）
  ├─ runtime.startup + addTransformer(CoverageTransformer)   ← 官方插桩器注册
  ├─ AgentEnhancer.afterStartup(agentOptions, inst)  ← 第 2 个钩子（注册顺序：插桩器之后）
  │     ├─ JarDownloadServer.start：启动下载服务 + 上报 action=reportHttpPort
  │     └─ inst.addTransformer(new DeclaredFieldsRewriter())
  └─ 业务类加载：CoverageTransformer（插桩）→ DeclaredFieldsRewriter（改写调用点）
```

要点：

- **端口修正必须在 `Agent.getInstance` 之前**（其内部会执行 TcpServerOutput.startup 绑定）；
- **改写器必须在插桩器之后注册**（JVM 按注册顺序回调，先插桩、后改写，栈帧不受影响）；
- 升级上游 JaCoCo 时，把这两行钩子插回新版 `PreMain.premain` 对应位置即可（见第 9 节）。

---

## 4. 门户上报协议

- 地址：`http://qa.fzzqft.com/portaljava/codeCoverage/agent`
- 方式：`POST application/json`，异步（daemon 线程），超时 3s
- 标识：`appName`（`APP_NAME` 环境变量）+ `env`（`FOUNDERSC_ENV`，发送时统一小写）
- **appName / env 任一缺失 → 跳过上报并打日志（不发送）**——避免向门户发送
  永远无法匹配的请求（门户按 `appName + env` 更新 `code_coverage_app` 表）

### action 一览

| action | 触发时机 | 字段 | 门户落库 |
|---|---|---|---|
| `reportDumpPort` | dump 端口被占 +1 后 | `appName / env / agentPort` | `agent_port` |
| `reportHttpPort` | 下载服务启动成功后 | `appName / env / httpPort` | `http_port`（`agentPort+100` 仅兜底） |

## 5. Jar 下载服务

- 框架：JDK 自带 `com.sun.net.httpserver.HttpServer`（不引入任何依赖）
- 端口：
  - 默认 `dump端口 + 100`；可 `-Djacoco.httpPort=xxxx` 覆盖
  - 被占用则自动 +1 重试（最多 5 次），最终以 `reportHttpPort` 上报值为准
- 接口：

```bash
# GET（query 中的路径必须做 URL 百分号编码！中文路径不编码会 400）
curl -G -o app.jar "http://<agent-ip>:<httpPort>/download" \
     --data-urlencode "path=/data/apps/app.jar"

# POST multipart/form-data（字段 path）亦可
```

- 路径规则：`.jar` / `.war` 后缀；相对路径按进程工作目录（user.dir）解析；
  **无白名单**（测试/内网环境限定，生产必须加白名单）
- 响应：`application/octet-stream` + `Content-Disposition: attachment`

---

## 6. `$jacocoData` 反射屏蔽

- 问题：jacoco 插桩注入静态字段 `$jacocoData`，业务代码/框架通过
  `Class.getDeclaredFields()` 反射遍历时把该字段当业务字段处理（序列化、Bean 拷贝等污染）
- 方案：`DeclaredFieldsRewriter` 作为 ClassFileTransformer 在插桩链之后运行，
  把插桩后字节码中 `INVOKEVIRTUAL Class.getDeclaredFields()` 改写为
  `INVOKESTATIC DeclaredFieldsRewriter.getDeclaredFields(Class)`：
  - 仅剔除 `$jacocoData`，**不滤全 synthetic**（不误伤 CGLIB 等代理字段）；
  - 无注入字段的类原样返回；栈形状不变，不破坏已有栈帧与探针布局；
  - 改写失败（畸形字节码）返回 null 保持原样，不影响类加载；
  - 目标类名通过 `DeclaredFieldsRewriter.class.getName()` 运行时求值，
    自动跟随 agent 打包（shade）后的重定位包名。

---

## 7. 日志体系（14 条打印，全部 `System.out`，前缀 `[jacoco-*]`）

| 阶段 | 前缀 | 日志 | 触发 |
|---|---|---|---|
| 启动 | `[jacoco-enhancer]` | `beforeStartup 失败: {e}` | 前置钩子异常（兜底，正常不出现） |
| 启动 | `[jacoco-download]` | `端口 {p} 被占用，尝试 {p+1}` | 下载端口冲突（每次重试一条） |
| 启动 | `[jacoco-download]` | `jacoco.httpPort 非法: {v}` | 系统属性配置错误 |
| 启动 | `[jacoco-download]` | `端口占用且重试次数用尽，下载服务不启动` | 5 次全失败 |
| 启动 | `[jacoco-download]` | `http 下载服务已启动: port={p}` | ✅ 正常启动必有 |
| 启动 | `[jacoco-download]` | `启动失败: {e}` | 下载服务异常（兜底） |
| 上报 | `[jacoco-{action}]` | `上报最新端口({port})跳过: APP_NAME/FOUNDERSC_ENV 未设置` | 标识缺失 |
| 上报 | `[jacoco-{action}]` | `上报最新端口({port})成功: {响应}` | 门户 200 |
| 上报 | `[jacoco-{action}]` | `上报最新端口({port})失败: {msg}` | 门户非 200 |
| 上报 | `[jacoco-{action}]` | `上报最新端口({port})失败: {ex}` | 网络异常 |
| 下载 | `[jacoco-download]` | `下载失败: {msg}` | 请求非法（400） |
| 下载 | `[jacoco-download]` | `下载失败: not found or not allowed` | 404 |
| 下载 | `[jacoco-download]` | `下载失败(传输中断): {ex}` | 客户端断开等 |
| 下载 | `[jacoco-download]` | `下载完成: {name}, {size} bytes, {ms} ms` | ✅ 成功 |

排查用法：`grep -E "jacoco" app.log` 一次拉出全部定制日志。

---

## 8. 构建与验证

```bash
# 全量测试（agent.rt.test 100/100，含 core.test 等全套 994 个用例通过）
mvn -pl org.jacoco.agent.rt.test -am test -Djacoco.skip=true

# 打包 jacocoagent.jar
mvn -pl org.jacoco.agent,org.jacoco.agent.rt -am package -DskipTests
# 产物：org.jacoco.agent/target/classes/jacocoagent.jar

# 使用
java -javaagent:/path/jacocoagent.jar=output=tcpserver,port=6300,address=0.0.0.0 -jar app.jar
```

> 备注：测试与打包时 `-Djacoco.skip=true` 是因为本项目测试自举 jacoco agent
> 在部分 reactor 构建下解析不到安装好的 jar（非代码问题，全量 `mvn install` 后无此现象）。

---

## 9. 升级 JaCoCo 源码步骤（本次重构的核心目标）

1. **覆盖上游文件**：`org.jacoco.core`、`org.jacoco.core.test`、`org.jacoco.agent.rt` 的
   原文件（除 PreMain 外）均为官方原样，直接取新版源码替换，零冲突；
2. **PreMain 接线**（唯一手工步骤）：在新版 `PreMain.premain` 中按官方流程插回：

```java
final AgentOptions agentOptions = new AgentOptions(options);
AgentEnhancer.beforeStartup(agentOptions);      // 端口探测与上报（getInstance 之前）
final Agent agent = Agent.getInstance(agentOptions);
// ... 官方 runtime.startup / addTransformer(CoverageTransformer) ...
AgentEnhancer.afterStartup(agentOptions, inst); // 下载服务 + 改写器（插桩器之后注册）
```

3. **保留构建配置**：`org.jacoco.agent.rt/pom.xml` 的
   `maven-compiler-plugin <source>8</source><target>8</target>`（保证 agent 产物 Java 8 字节码，
   在老 BES 环境可运行）；
4. **门户同步**：门户 `AppManageServiceImpl.agent()` 的 action 分支
   （`reportDumpPort` / `reportHttpPort`）；
5. **回归**：跑第 8 节命令 + 真机启动验证日志。

---

## 10. 已知限制与生产加固项

| 项 | 现状 | 生产建议 |
|---|---|---|
| 下载路径 | 任意路径（仅限 `.jar/.war`） | 加白名单校验 |
| multipart body | 全量读入内存、无大小上限 | `Content-Length > 4KB` 直接 400（见第 5 节） |
| 端口竞态 | 探测与绑定间毫秒级窗口（BES 高频重启极小概率） | 观察即可，必要时在 TcpServerOutput 侧做第二次兜底 |
| 上传/注册 | 已删除（注册由门户 kafka 接管；上传无调用方且无门户对端） | — |

---

## 11. 常见问题

| 现象 | 原因与处理 |
|---|---|
| 下载返回 400 `URISyntaxException` | 路径未做 URL 编码（中文路径/空格必踩）。命令行用 `curl -G --data-urlencode`，门户 Java 端已用 `URLEncoder.encode` |
| 应用启动看不到 `[jacoco-download] 已启动` | 检查 App 日志是否 `APP_NAME/FOUNDERSC_ENV` 缺失（上报跳过），或下载端口 5 次冲突后放弃 |
| 上报日志出现 `跳过` | `APP_NAME` / `FOUNDERSC_ENV` 环境变量未注入，门户侧不会出现该应用记录 |
| 门户记录的端口和 agent 实际端口不一致 | 以 agent 日志 `http 下载服务已启动: port=` 与 `上报最新端口(...)` 为准；`reportHttpPort` 上报覆盖 `agentPort+100` 推算值 |
