# JaCoCo Agent 定制扩展说明（customization）

> 本仓库在官方 JaCoCo 基础上做了一组定制扩展，用于对接公司内网质量门户
> （`http://qa.fzzqft.com/portaljava/codeCoverage/agent`），并解决两类运行时问题：
> 老中间件（宝兰德 BES）的 dump 端口冲突、`$jacocoData` 对反射/序列化链路的污染。
>
> 设计原则：
>
> 1. **全部定制代码集中在 `org.jacoco.agent.rt.internal.custom` 子目录**；
> 2. 官方文件只保留两处接线：`PreMain` 的 1 行 import + 2 行钩子，以及
>    `org.jacoco.agent.rt/pom.xml` 的 Java 8 编译配置；
> 3. 所有增强逻辑异常不外传，**绝不阻断 agent / 业务启动**；
> 4. 升级上游 JaCoCo 时，拷贝 `custom/` 目录 + 补回接线即可，零冲突。
>
> 本文档与定制代码同目录，迁移时整体拷贝。

---

## 1. 能力总览

| 能力 | 说明 |
|---|---|
| 端口冲突探测 | dump 端口被占（BES `address in use`）时提前 +1，并上报实际端口 |
| 门户上报 | 异步上报 dump 端口、下载端口、应用本体 jar 路径 |
| 产物下载 | 内嵌 HTTP 服务，门户按路径拉取 jar/war |
| 自动识别 | `path=auto`：自动识别 `java -jar` 本体 jar、WAR、解压目录 |
| WAR 支持 | 打包 WAR 直接返回原文件；解压目录按 WAR 布局现场打包 |
| 目录浏览 | `GET /browse` 只读返回目录树元数据（测试环境排查用） |
| 默认 token | 内置 `albert-einstein`，所有 HTTP 接口校验 `X-Agent-Token`；可覆盖或 `off` 关闭 |
| `$jacocoData` 屏蔽 | 改写 `Class.getDeclaredFields()` 调用点，剔除 JaCoCo 注入字段 |
| 失败安全 | 所有增强逻辑都有异常兜底，不阻断业务启动 |

---

## 2. 目录结构与文件清单

```
org.jacoco.agent.rt/src/org/jacoco/agent/rt/internal/
├── PreMain.java                     ← 官方文件（仅 +1 import、+2 钩子）
├── Agent.java / AgentModule.java / ...      ← 官方文件，原样
└── custom/                          ← 全部定制代码
    ├── AgentEnhancer.java
    ├── ApplicationJarResolver.java
    ├── DeclaredFieldsRewriter.java
    ├── DirectoryBrowser.java
    ├── JarDownloadServer.java
    ├── SimpleHttpUtil.java
    ├── WebappArtifactResolver.java
    └── customization.md             ← 本文档
```

生产代码职责：

| 文件 | 职责 |
|---|---|
| `AgentEnhancer.java` | 生命周期钩子：端口探测、启动下载服务、注册改写器（PreMain 唯一入口） |
| `JarDownloadServer.java` | 内嵌 HTTP 服务：`/download`、`/browse`、token 校验、端口上报 |
| `SimpleHttpUtil.java` | 门户 HTTP 客户端：上报 body 组装、IP 采集、JSON 转义、超时控制 |
| `DeclaredFieldsRewriter.java` | `$jacocoData` 后处理改写 + 过滤后的 `getDeclaredFields` |
| `ApplicationJarResolver.java` | `java -jar` 场景识别应用本体 jar |
| `WebappArtifactResolver.java` | WAR / 解压目录识别、WAR 布局打包、结果缓存 |
| `DirectoryBrowser.java` | 只读目录树浏览（流式、限深限量） |

测试（`org.jacoco.agent.rt.test/src/org/jacoco/agent/rt/internal/custom/`）：

| 文件 | 覆盖 |
|---|---|
| `AgentEnhancerTest.java` | 端口冲突 +1 / 空闲不变 / 非法端口不抛异常 |
| `DeclaredFieldsRewriterTest.java` | 调用点改写 / 穿透 / 过滤 / 无改写返回 null / 畸形字节码不抛 |
| `SimpleHttpUtilTest.java` | 上报 body、IP-only 身份、JSON 转义 |
| `ApplicationJarResolverTest.java` | 绝对/相对路径、`-cp` 单/多条目、非 jar |
| `WebappArtifactResolverTest.java` | WAR/解压目录识别、多应用匹配、缓存、STORED 打包 |
| `DirectoryBrowserTest.java` | 深度/数量上限、过滤、符号链接、JSON 转义 |
| `JarDownloadServerHttpTest.java` | GET 可用、POST 拒绝、token 校验 |

官方文件改动（共 2 处）：

| 文件 | 改动 |
|---|---|
| `PreMain.java` | `import ...internal.custom.AgentEnhancer;` + `beforeStartup` / `afterStartup` 两行钩子 |
| `org.jacoco.agent.rt/pom.xml` | `maven-compiler-plugin` 使用 `release=8`，同时约束语法、字节码版本与 API 集合 |

其余历史上改过的官方文件（`TcpServerOutput`、`MethodProbesAdapter`、
`MethodProbesAdapterTest` 等）均已恢复官方原样。

---

## 3. 启动流程与时序

```
JVM 启动（-javaagent → premain）
  ├─ AgentEnhancer.beforeStartup(agentOptions)          ← Agent.getInstance 之前
  │     ├─ adjustPortOnConflict：探测 dump 端口，被占则 +1
  │     └─ reportDumpPort：异步上报实际 dump 端口
  ├─ Agent.getInstance(agentOptions)                     ← 官方初始化（TcpServerOutput 绑定）
  ├─ runtime.startup + addTransformer(CoverageTransformer)   ← 官方插桩器注册
  └─ AgentEnhancer.afterStartup(agentOptions, inst)      ← 插桩器之后
        ├─ JarDownloadServer.start：启动下载服务 + reportHttpPort
        ├─ ApplicationJarResolver.resolve → reportJarPath（java -jar 场景）
        ├─ WebappArtifactResolver.init(inst)：保存 Instrumentation 供按需识别
        └─ addTransformer(DeclaredFieldsRewriter)
  └─ 业务类加载：CoverageTransformer（插桩）→ DeclaredFieldsRewriter（改写调用点）
```

关键顺序：

1. **端口修正必须在 `Agent.getInstance` 之前**（其内部会执行 `TcpServerOutput.startup` 绑定，端口被占会导致 premain 失败、应用起不来）；
2. **改写器必须在插桩器之后注册**（JVM 按注册顺序回调：先插桩、后改写，栈帧形状不变）；
3. `afterStartup` 内每一步都有 try/catch，任何失败只打日志，不影响业务启动。

---

## 4. 门户上报协议

### 4.1 基本约定

- 地址：`http://qa.fzzqft.com/portaljava/codeCoverage/agent`
- 方式：`POST application/json`，异步 daemon 线程，连接/读取超时各 3 秒
- 身份：`ip`（agent 本机非回环 IPv4 列表）**或** `appName + env` 至少一组
  - `appName` 取 `APP_NAME` 环境变量，`env` 取 `FOUNDERSC_ENV` 并统一转小写
  - 多网卡时 `ip` 为逗号分隔列表
- 所有字段值做 JSON 转义，避免路径中的引号/反斜杠破坏报文
- 上报失败只打日志，不影响业务

### 4.2 action 一览

| action | 触发时机 | 主要字段 | 门户落库 |
|---|---|---|---|
| `reportDumpPort` | dump 端口被占 +1 后 | `appName / env / ip / agentPort` | `agent_port` |
| `reportHttpPort` | 下载服务启动成功后 | `appName / env / ip / httpPort` | `http_port`（`agentPort+100` 仅兜底） |
| `reportJarPath` | `java -jar` 且识别到本体 jar | `appName / env / ip / jarPath` | `jar_path` |

报文示例：

```json
{"action":"reportHttpPort","appName":"group_app","env":"qa","ip":"10.0.0.1","httpPort":"6400"}
{"action":"reportJarPath","ip":"10.0.0.1","jarPath":"/opt/app/lib/app-1.0.0.jar"}
```

### 4.3 门户侧匹配规则

1. **IP 优先**：在 `code_coverage_app.deploy_ip`（JSON 数组）中查找包含任一上报 IP 的应用，
   且要求唯一命中；命中多个时优先 `app_name` 相同的行；
2. **appName + env 兜底**：IP 对不上（如容器内网卡地址不在 deploy_ip 中）时使用；
3. 仍未匹配 → 返回业务错误并打 warn 日志，不写入任何数据。

> 这样设计是为了绕开 `APP_NAME` 与库中 `app_name` 不一致导致
> `update` 影响 0 行、但接口仍返回成功的问题。

---

## 5. 产物下载与自动识别

### 5.1 `/download`

- 仅支持 **GET**；POST 等其他方法返回 400（不读取请求体，避免占用业务 JVM 内存）
- 参数：`path`，必须是已存在的 `.jar` / `.war` 文件；相对路径按 `user.dir` 解析
- 响应：`application/octet-stream` + `Content-Disposition: attachment`，8KB 缓冲流式输出
- 不存在或后缀非法 → 404 `not found or not allowed`

```bash
# 路径中的中文/空格必须 URL 编码
curl -G -o app.jar "http://<agent-ip>:<httpPort>/download" \
     --data-urlencode "path=/data/apps/app.jar"
```

### 5.2 `path=auto`（自动识别）

```bash
curl -o app.bin "http://<agent-ip>:<httpPort>/download?path=auto"
```

识别顺序：

```
1. ApplicationJarResolver（java -jar）
     sun.java.command 首个 token 是 .jar → 返回该 jar
     否则 java.class.path 仅一个 .jar 条目 → 返回该 jar
2. WebappArtifactResolver（WAR / 解压目录）
     CodeSource 定位 webapp 根
       有同名 .war → 返回原始 war
       只有解压目录 → 按 WAR 布局现场打包
     CodeSource 拿不到 → 扫描 catalina.base/home、user.dir、常见 Tomcat/BES 目录
     多个候选且 APP_NAME 无法区分 → 返回 null（不猜）
```

**CodeSource 识别的 URL 形态：**

| 部署形态 | CodeSource |
|---|---|
| 打包 WAR（未解压） | `jar:file:/.../app.war!/WEB-INF/classes/` |
| 解压 WAR | `file:/.../webapps/app/WEB-INF/classes/` |
| 业务类在 lib 中 | `file:/.../webapps/app/WEB-INF/lib/app-core.jar` |

**解压目录的合成规则：**

- 保持 WAR 布局：`WEB-INF/classes/**/*.class` + `WEB-INF/lib/*.jar`；
- class 条目用 DEFLATED，jar 条目用 **STORED**（先算 CRC/size 再流式写），
  避免在业务 JVM 里对已压缩的 jar 重复做 DEFLATE；
- 大小未知，使用 chunked 传输；
- 门户侧复用现有 WAR 解析逻辑，按源码 pom 的 groupId 过滤项目模块。

**缓存策略：**

- 识别成功一次后永久复用（同一 JVM 内路径稳定）；
- 识别失败时 10 秒内不重复扫描，避免异常流量反复触发 `getAllLoadedClasses()`。

### 5.3 可选 token 鉴权

#### 配置方式

两种方式二选一，**都写在 JVM 参数或环境变量里，不要写进 `-javaagent:...=` 的 agent 参数**：

```bash
# 方式一：JVM 系统属性（必须位于 -jar 之前）
java -Djacoco.httpToken=your-token \
     -javaagent:/path/jacocoagent.jar=output=tcpserver,port=6300,address=0.0.0.0 \
     -jar app.jar

# 方式二：环境变量
JACOCO_AGENT_TOKEN=your-token \
java -javaagent:/path/jacocoagent.jar=output=tcpserver,port=6300 \
     -jar app.jar
```

注意事项：

- `-Djacoco.httpToken` 是 JVM 参数，必须放在 `-jar app.jar` **之前**；放到 `-jar` 后会被当成应用参数；
- **不要写成 `-javaagent:...=httpToken=xxx`**：官方 `AgentOptions` 只接受白名单参数，未知参数会抛
  `IllegalArgumentException: Unknown agent option "httpToken"`，premain 失败会导致应用无法启动；
- 两者同时配置时，`-Djacoco.httpToken` 优先；
- 两者都不配置 → 使用内置默认 token **`albert-einstein`**（鉴权默认开启）；
- 显式配置为 `off`（大小写不敏感，属性或环境变量均可）→ 关闭鉴权，仅用于测试环境。

> 默认 token 写在源码/jar 中，不算真正的密钥，防的是内网随手访问；
> 生产环境请通过 `JACOCO_AGENT_TOKEN` 覆盖为实际值。

#### 请求方式

启用后所有接口（`/download`、`/browse`）必须携带 token：

```bash
curl -H "X-Agent-Token: your-token" \
     "http://<agent-ip>:<httpPort>/browse?path=/opt/app&depth=2"

curl -H "X-Agent-Token: your-token" -o app.jar \
     "http://<agent-ip>:<httpPort>/download?path=/opt/app/lib/app-1.0.0.jar"
```

也支持标准写法：

```
Authorization: Bearer your-token
```

- 比较使用 `MessageDigest.isEqual`（常量时间）；
- 未携带或错误 → `401 unauthorized`。

#### 门户侧配置

```yaml
custom:
  agentToken: ${JACOCO_AGENT_TOKEN:albert-einstein}
```

- 门户下载时自动发送 `X-Agent-Token`（`/download` 与 `path=auto` 均生效）；
- 默认值与 agent 内置 token 一致，因此门户与 agent 都未配置时也能直接工作；
- 如果覆盖了 agent 的 token，门户容器必须注入**相同的** `JACOCO_AGENT_TOKEN`；
- 配置为 `off`（或为空）时门户不发送 header，需与 agent 的 `off` 同步使用。

#### 部署示例

k8s（从 Secret 注入）：

```yaml
env:
  - name: JACOCO_AGENT_TOKEN
    valueFrom:
      secretKeyRef:
        name: jacoco-agent-token
        key: token
```

docker：

```bash
docker run -e JACOCO_AGENT_TOKEN=your-token ...
```

> 安全提示：`-D` 与 `-javaagent` 参数都会出现在 `ps` / `/proc/<pid>/cmdline` 中；
> 环境变量位于 `/proc/<pid>/environ`，权限更严格，生产环境优先使用环境变量 + Secret 注入。

### 5.4 稳定性保护

| 保护 | 现状 |
|---|---|
| 请求并发 | 固定 4 线程 + 16 队列 + `CallerRunsPolicy`（队列满时背压，不无限建线程） |
| 文件输出 | 8KB 缓冲流式输出，不整体载入内存 |
| 产物落盘 | 先写 `<target>.part`，zip 校验通过后原子替换；失败自动清理，不留半成品 |
| 自动识别 | 结果缓存 + 失败 10 秒退避 |
| 解压目录打包 | 流式 zip，jar 用 STORED，内存与文件大小无关 |
| 日志 | `System.out`，daemon 上报线程 |

---

## 6. 目录浏览 `/browse`

仅用于测试/内网排查，只返回元数据，不返回文件内容。

```bash
curl "http://<agent-ip>:<httpPort>/browse?path=/opt/app&depth=2&filter=jar,war"
```

| 参数 | 默认 | 硬上限 | 说明 |
|---|---|---|---|
| `path` | `user.dir` | — | 必须是目录；相对路径按工作目录解析 |
| `depth` | 2 | 4 | 展开层级（直接子项为 1） |
| `filter` | 空 | — | 逗号分隔扩展名，只过滤文件，目录始终列出 |
| `limit` | 200 | 500 | 返回条目总数（目录 + 文件），超出置 `truncated=true` |

响应示例：

```json
{
  "root": "/opt/app",
  "depth": 2,
  "limit": 200,
  "filter": "jar,war",
  "truncated": false,
  "entries": [
    {"path":"/opt/app/lib","relative":"lib","type":"dir","size":0,"mtime":"2026-11-12 10:00:00","depth":1},
    {"path":"/opt/app/lib/app-1.0.0.jar","relative":"lib/app-1.0.0.jar",
     "type":"file","size":137613370,"mtime":"2026-11-12 10:00:00","depth":2}
  ]
}
```

- `DirectoryStream` 流式遍历，不构建整目录数组；
- 符号链接不跟随，标记 `type=link`；
- 遍历结束后仅对已收集（最多 `limit` 条）的结果排序：目录优先、再按名称。

---

## 7. `$jacocoData` 反射屏蔽

- 问题：JaCoCo 插桩会给类注入静态字段 `$jacocoData`。业务代码/框架通过
  `Class.getDeclaredFields()` 遍历字段时，会把它当作业务字段处理（序列化、Bean 拷贝等污染）。
- 方案：`DeclaredFieldsRewriter` 作为 `ClassFileTransformer` 在插桩器之后运行，
  把字节码中的 `INVOKEVIRTUAL Class.getDeclaredFields()` 改写为
  `INVOKESTATIC DeclaredFieldsRewriter.getDeclaredFields(Class)`。
- 安全性：
  - 只改写**目标类加载器能解析到本类**的类；bootstrap 类（JDK）与隔离类加载器跳过，
    避免运行时 `NoClassDefFoundError`（详见附录事故复盘）；
  - 加载器可见性结果用 `WeakHashMap` 缓存，避免重复 `Class.forName`；
  - 解析前先做一次廉价的字节码字符串预检，常量池中没有 `getDeclaredFields` 的类直接跳过；
  - 仅剔除 `$jacocoData`，不滤全部 synthetic，不误伤 CGLIB 等代理字段；
  - 改写失败（畸形字节码）返回 null，保持原类加载；
  - 目标类名运行时通过 `DeclaredFieldsRewriter.class.getName()` 获取，
    自动跟随 shade 重定位后的包名。

---

## 8. 日志体系

全部使用 `System.out`，统一前缀 `[jacoco-*]`：

| 阶段 | 日志 | 触发 |
|---|---|---|
| 启动 | `[jacoco-enhancer] beforeStartup 失败: {e}` | 前置钩子异常（兜底） |
| 启动 | `[jacoco-download] 端口 {p} 被占用，尝试 {p+1}` | 下载端口冲突重试 |
| 启动 | `[jacoco-download] jacoco.httpPort 非法: {v}` | 系统属性配置错误 |
| 启动 | `[jacoco-download] 端口占用且重试次数用尽，下载服务不启动` | 5 次全失败 |
| 启动 | `[jacoco-download] http 下载服务已启动: port={p}` | ✅ 正常启动 |
| 启动 | `[jacoco-download] 启动失败: {e}` | 下载服务异常（兜底） |
| 启动 | `[jacoco-enhancer] 注册 $jacocoData 改写器失败: {e}` | transformer 注册异常 |
| 识别 | `[jacoco-reportJarPath] 识别到本体 jar: {path}` | java -jar 识别成功 |
| 识别 | `[jacoco-reportJarPath] 未能识别本体 jar（非 java -jar 启动），跳过` | -cp / 目录部署 |
| 识别 | `[jacoco-reportJarPath] 上报失败: {e}` | 上报异常 |
| 上报 | `[jacoco-{action}] 上报({value})跳过: APP_NAME/FOUNDERSC_ENV/IP 均未获取到` | 无身份信息 |
| 上报 | `[jacoco-{action}] 上报({value})成功: {响应}` | 门户 200 |
| 上报 | `[jacoco-{action}] 上报({value})失败: {msg}` | 非 200 / 网络异常 |
| 下载 | `[jacoco-download] 拒绝未授权请求: {uri}` | token 不匹配 |
| 下载 | `[jacoco-download] 下载失败: {msg}` | 400（方法/参数非法） |
| 下载 | `[jacoco-download] 下载失败: not found or not allowed` | 404 |
| 下载 | `[jacoco-download] 下载失败(传输中断): {e}` | 客户端断开等 |
| 下载 | `[jacoco-download] 下载完成: {name}, {size} bytes, {ms} ms` | ✅ 成功 |
| 自动识别 | `[jacoco-download] 自动识别失败: 未找到 WAR / 解压目录` | 识别不到 |
| 自动识别 | `[jacoco-download] 自动识别下载完成(jar/WAR/WEBAPP_DIR): {path}` | ✅ 成功 |
| 其他 | `[jacoco] 获取本机 IP 失败: {e}` | 网卡枚举异常 |

排查：`grep -E "jacoco" app.log` 可一次拉出全部定制日志。

---

## 9. 构建、测试与验证

### 9.1 测试

```bash
# agent 全量测试（core + agent.rt.test）
mvn -pl org.jacoco.agent.rt.test -am test -Djacoco.skip=true

# 只跑定制相关测试
mvn -pl org.jacoco.agent.rt.test -am test \
  -Dtest='AgentEnhancerTest,ApplicationJarResolverTest,DeclaredFieldsRewriterTest,DirectoryBrowserTest,JarDownloadServerHttpTest,SimpleHttpUtilTest,WebappArtifactResolverTest' \
  -DfailIfNoTests=false -Djacoco.skip=true
```

> 构建环境建议 JDK 17+（本机验证使用 21）；agent 产物通过 `release=8`
> 同时约束 Java 8 语法、字节码版本与 API 集合，可在老 BES 环境运行。

### 9.2 打包 `jacocoagent.jar`

```bash
mvn -pl org.jacoco.agent,org.jacoco.agent.rt -am package -DskipTests
```

- 必须同时包含 `org.jacoco.agent.rt`（编译定制源码 + shade ASM）与
  `org.jacoco.agent`（合并 core + agent.rt + ASM、重定位包名、写 manifest）；
- `-am` 连带构建依赖模块，保证产物为最新代码；
- 产物：

| 文件 | 说明 |
|---|---|
| `org.jacoco.agent/target/classes/jacocoagent.jar` | **最终使用的 agent jar** |
| `org.jacoco.agent/target/org.jacoco.agent-0.8.14-SNAPSHOT.jar` | 主 jar（内容相同） |

**产物校验：**

```bash
# 1) Premain-Class 指向重定位后的 PreMain（后缀为构建提交的短 hash）
unzip -p org.jacoco.agent/target/classes/jacocoagent.jar META-INF/MANIFEST.MF | grep -i premain
#   → Premain-Class: org.jacoco.agent.rt.internal_<hash>.PreMain

# 2) 定制类位于重定位包的 custom/ 子目录
unzip -l org.jacoco.agent/target/classes/jacocoagent.jar | grep "internal_.*/custom/"
```

> `customization.md` 也会被构建复制进 jar 的 `custom/` 下（约 20KB），
> 属于预期行为；如需排除，可在 shade 配置中增加 exclude。

**使用：**

```bash
# 基础用法（dump 端口 6300，下载端口 6400）
java -javaagent:/path/jacocoagent.jar=output=tcpserver,port=6300,address=0.0.0.0 -jar app.jar

# 指定下载端口 + token
java -Djacoco.httpPort=6400 -Djacoco.httpToken=xxxx \
     -javaagent:/path/jacocoagent.jar=output=tcpserver,port=6300 -jar app.jar

# 看到 [jacoco-download] http 下载服务已启动: port=6400 即定制生效
```

**安装到本地仓库**（ant / maven 插件 / 其它模块引用时需要）：

```bash
mvn -pl org.jacoco.agent -am install -DskipTests
```

### 9.3 常见构建失败

| 现象 | 处理 |
|---|---|
| `spotless-maven-plugin ... format violations` | 先执行 `mvn -pl org.jacoco.agent.rt,org.jacoco.agent.rt.test spotless:apply` 再打包 |
| `Failed to resolve artifact org.jacoco.agent.rt:jar:all` | agent.rt 的 shade 产物未安装，先执行上面的 `install` |
| Spotless 报 `Index -1 out of bounds` | Eclipse JDT 4.30 的 formatter bug（已遇到数字下划线字面量触发）；改用普通字面量或升级 formatter（需 JDK 17+） |

> 测试时使用 `-Djacoco.skip=true`：本项目测试会自举 JaCoCo agent，部分 reactor
> 构建下 `prepare-agent` 解析到 `target/classes` 会导致 fork VM 崩溃（非代码问题，
> 全量 `mvn install` 后正常）。

---

## 10. 升级 JaCoCo 源码步骤

1. **拷贝定制目录**：把 `org.jacoco.agent.rt/src/.../internal/custom/` 与
   `org.jacoco.agent.rt.test/src/.../internal/custom/` 整体拷入新版源码；
   该目录之外的文件均视为官方文件；
2. **覆盖上游文件**：`org.jacoco.core`、`org.jacoco.core.test`、`org.jacoco.agent.rt`
   的原文件（除 PreMain 外）直接取新版源码替换，零冲突；
3. **PreMain 接线**（唯一手工步骤）：

```java
import org.jacoco.agent.rt.internal.custom.AgentEnhancer;

final AgentOptions agentOptions = new AgentOptions(options);
AgentEnhancer.beforeStartup(agentOptions);      // 端口探测与上报（getInstance 之前）
final Agent agent = Agent.getInstance(agentOptions);
// ... 官方 runtime.startup / addTransformer(CoverageTransformer) ...
AgentEnhancer.afterStartup(agentOptions, inst); // 下载服务 + 改写器（插桩器之后）
```

4. **保留构建配置**：`org.jacoco.agent.rt/pom.xml` 的
   `maven-compiler-plugin <release>8</release>`（同时约束语法、字节码与 API）；
5. **门户同步**：门户 `AppManageServiceImpl.agent()` 的三个 action 分支
   （`reportDumpPort` / `reportHttpPort` / `reportJarPath`）；
6. **回归**：执行第 9 节测试与打包命令，再做一次真机启动验证。

---

## 11. 已知限制与生产加固项

| 项 | 现状 | 生产建议 |
|---|---|---|
| 下载路径 | 任意路径（仅限制 `.jar/.war`） | 增加路径白名单 |
| 鉴权 | 默认 token `albert-einstein`；可显式覆盖或 `off` 关闭 | 生产通过 `JACOCO_AGENT_TOKEN` 覆盖默认值 |
| 目录浏览 | 只读、仅元数据；不返回文件内容 | 仅测试/内网使用 |
| 请求方法 | 仅支持 GET（POST 已移除，无 body 内存风险） | — |
| 端口竞态 | 端口探测与官方绑定之间有极小时间窗口 | 观察即可；必要时在 `TcpServerOutput` 侧做二次兜底 |
| 多应用同 JVM | 多个 webapp 无法用 `APP_NAME` 区分时返回 null，不猜测 | 拆分部署或显式配置 `jar_path` |
| 上传/注册 | 已删除（注册由门户 Kafka 接管） | — |

---

## 12. 常见问题

| 现象 | 原因与处理 |
|---|---|
| 下载返回 400 `URISyntaxException` | 路径未做 URL 编码（中文/空格必踩）；命令行用 `curl -G --data-urlencode`，门户 Java 端已用 `URLEncoder.encode` |
| 应用启动看不到 `[jacoco-download] 已启动` | 检查 agent 是否生效（`Premain-Class` / `-javaagent` 参数），或下载端口 5 次冲突后放弃 |
| 上报日志出现 `跳过` | `APP_NAME` / `FOUNDERSC_ENV` 与 IP 均未获取到；检查环境变量与网卡 |
| 门户记录的端口与 agent 实际端口不一致 | 以日志 `http 下载服务已启动: port=` 为准；`reportHttpPort` 会覆盖 `agentPort+100` 推算值 |
| 请求返回 401 `unauthorized` | 未带 `X-Agent-Token` 或值不匹配；默认 token 为 `albert-einstein`，门户需配置同值，或两边同时配置 `off` 关闭 |
| 启动失败：`Unknown agent option "httpToken"` | token 不能写在 `-javaagent:...=` 里；改用 `-Djacoco.httpToken`（放在 `-jar` 之前）或 `JACOCO_AGENT_TOKEN` |
| `path=auto` 返回 404 | 未识别到产物；用 `/browse` 查看目录，或手工配置 `jar_path` |
| `/browse` 返回 404 | `path` 不是目录或不存在；`depth/limit` 非法会返回 400 |
| `jar_path` 与 `http_port` 一直不落库 | 检查门户日志 `JaCoCoAgent上报未匹配到应用`，确认上报 IP 是否在 `deploy_ip` 中 |
| 大目录浏览触发 `truncated=true` | 加 `filter=jar,war` 或缩小 `depth`；`limit` 上限 500 |

---

## 13. 附录：`$jacocoData` 事故复盘（通俗版，2026-09 线上问题）

### 13.1 起因

JaCoCo 通过在方法里注入探针来统计覆盖情况，并为每个被插桩类注入静态字段
`$jacocoData` 保存计数信息。该字段在类文件中真实存在，只是带 synthetic 标记。

### 13.2 问题

序列化、Bean 拷贝、反射工具会通过 `Class.getDeclaredFields()` 无差别遍历字段，
把 `$jacocoData` 当作业务字段处理，造成多余字段、反序列化失败等污染。

### 13.3 最初的解决办法

不改字段本身，而是把 `getDeclaredFields()` 的调用点改写为"过滤版"：
先正常取字段，再剔除 `$jacocoData` 返回。只剔 JaCoCo 这一个字段，
CGLIB 等代理生成的字段不受影响。

### 13.4 重构后为什么崩

改写动作被移到了独立的"后处理"环节。该环节没有区分类加载器，把 JDK 自己的类
（如 `java.io.ObjectStreamClass`，由 bootstrap 加载）也改写了。JDK 类在运行时
找不到 `DeclaredFieldsRewriter`（bootstrap 加载器看不到 agent 的类），直接抛
`NoClassDefFoundError`，导致序列化链路全挂、应用启动失败；同理也影响了
使用隔离类加载器的 OTel agent。

### 13.5 现在的对策

改写前先判断"目标类加载器能否解析到 `DeclaredFieldsRewriter`"：

- bootstrap 类 → 不碰；
- 隔离类加载器 → 不碰；
- 应用类（能看到 agent 类）→ 正常改写。

同时增加字节码预检与加载器可见性缓存，减少 ASM 解析和重复 `Class.forName`。

### 13.6 一句话总结

| | 内容 |
|---|---|
| 起因 | 插桩注入 `$jacocoData`，反射/序列化工具把它当业务字段 |
| 思路 | 改写 `getDeclaredFields()` 调用点为过滤版 |
| 事故 | 改写范围失控，JDK 类找不到过滤方法 → 全局 NCDFE |
| 对策 | 按类加载器可见性甄别；不可见则不改写 |

---

## 14. 定制演进（提交对应）

| 提交 | 内容 |
|---|---|
| `d2c815a9` | 初始门户注册逻辑、PreMain 钩子雏形 |
| `177327be` | 宝兰德 BES dump 端口冲突处理（后迁移到 AgentEnhancer） |
| `36444877` / `38290b84` / `882fe9ef` | HTTP 上报实现、移除第三方依赖（okhttp 等） |
| `3372e9dd` | 曾尝试上传 jar 到门户（后删除） |
| `4526ee61` | `$jacocoData` 屏蔽 + 内嵌 JarDownloadServer |
| `0daa58b5` | 重构二开功能到独立文件，便于升级上游 |
| `9252fa30` / `34fc008c` / `70877481` / `cd2971a8` | 日志与细节优化 |
| 本次（未提交） | 自动识别（java -jar / WAR）、`/browse`、token、稳定性与命中率修复 |
