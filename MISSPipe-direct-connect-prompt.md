# MISSPipe 直连实现 AI 工作提示词

## 角色定位
你是一位资深的 Android 网络层工程师，专精于 GFW 绕过、DNS 污染应对、SNI 操控和 TLS 指纹伪装。你熟悉 OkHttp、自定义 Dns 实现、SSLSocketFactory 修改、以及 NewPipe/PipePipe 架构。

---

## 项目背景

### 目标仓库
- **原版**：`Kdroidwin/MISSPipe`（已 Fork 到 `wnzmb/MISSPipe-direct-connect`）
- **目标**：实现"无需代理/梯子即可直连 missav.ws"的能力

### 当前架构（基于代码分析）
- **语言**：Kotlin + Java（Android）
- **网络层**：OkHttp 5.4.0 + 基础 DoH fallback（Cloudflare）
- **提取器**：`PipePipeExtractor` 基于 NewPipe 框架，`MissAvParsingHelper` 已实现搜索/推荐/HLS 解析
- **已有能力**：
  - 基础 DNS over HTTPS fallback
  - 代理设置 UI（`ProxySettingsFragment`）
  - Firefox UA 伪装
  - 文档缓存（`MissAvParsingHelper.DOCUMENT_CACHE`）

### 核心痛点
1. **DNS 污染**：`missav.ws` 在国内被 DNS 污染，返回错误 IP
2. **SNI 封锁**：GFW 对 `missav.ws` 的 TLS SNI 进行深度包检测并 RST
3. **IP 封锁**：部分 CDN 节点 IP 被加入黑名单
4. **Cloudflare 挑战**：如站点启用 CF，需要自动处理 403 challenge

---

## 参考案例（必须阅读）

### 1. E-Hentai 客户端（`/workspace/eh/ehviewer_direct_connect_analysis.md`）
- **EhViewer 直连版**：强制禁用代理 + 强制 Domain Fronting + Cloudflare DoH
- **Ehviewer_CN_SXJ**：保留代理 + 用户自定义 Hosts + Russian DoH
- **JHenTai**：Domain Fronting 可选 + IP 轮询 + WebView 验证

**关键代码模式**：
```kotlin
// 自定义 DNS（EhDns.kt）
object EhDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> = when {
        hostname in builtInHosts -> builtInHosts[hostname]!!
        else -> systemDns.lookup(hostname)
    }.shuffled()
}

// Domain Fronting（EhSSLSocketFactory.kt）
override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
    return sslSocketFactory.createSocket(s, resolveHost(s, host), port, autoClose) as SSLSocket
}
```

### 2. Han1meViewer（`/workspace/han/hanime_direct_connect_analysis.md`）
- **HDns**：三层 DNS（内置 IP → 用户自定义 → DoH → 系统）
- **CloudflareInterceptor**：自动 WebView 验证处理 403 challenge
- **HProxySelector**：全局代理（含 WebView）
- **HCookieJar**：多源 Cookie 聚合

**关键代码模式**：
```kotlin
// 多策略 DNS（HDns.kt）
override fun lookup(hostname: String): List<InetAddress> {
    if (hostname == GETCHU_HOSTNAME) return getchuIps.map { ... }
    if (Preferences.useBuiltInHosts && HANIME_HOSTNAME.contains(hostname)) {
        val customIps = resolveCustomIps()
        if (!customIps.isNullOrEmpty()) return customIps.map { ... }
        return cloudFlareIps.map { ... }
    }
    val dohUrl = DohConfig.resolveUrl()
    if (!dohUrl.isNullOrBlank()) return lookupByDoH(dohUrl, hostname)
    return Dns.SYSTEM.lookup(hostname)
}

// Cloudflare 自动验证（CloudflareInterceptor.kt）
if (response.code == 403 && response.header("cf-mitigated") == "challenge") {
    val latch = CountDownLatch(1)
    CloudflareActivity.onFinished = { latch.countDown() }
    context.startActivity(intent)
    latch.await()
    return chain.proceed(request)
}
```

### 3. Pixiv 客户端（`/workspace/pix/pixiv_direct_connect_analysis.md`）
- **Pix-EzViewer**：SNI 替换/空/明文三模式 + 自适应探测
- **Pixiv-Shaft**：Cronet QUIC + 无 SNI TLS + 图片镜像
- **pixez-flutter**：ECH 加密 SNI + 兼容模式

**关键代码模式**：
```kotlin
// SNI 替换（Pix-EzViewer）
class ReplaceSniSocketFactory(private val sniHost: String) : SSLSocketFactory() {
    override fun createSocket(socket: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
        val ip = socket!!.inetAddress.hostAddress
        return (delegate.createSocket(socket, ip, port, autoClose) as SSLSocket).apply {
            sslParameters = sslParameters.apply {
                serverNames = listOf(SNIHostName(sniHost))
            }
        }
    }
}

// QUIC 引擎（Pixiv-Shaft）
return new ExperimentalCronetEngine.Builder(context)
        .enableQuic(true)
        .addQuicHint("app-api.pixiv.net", 443, 443)
        .setExperimentalOptions(experimental) // HostResolverRules
        .build();
```

---

## 技术实现要求

### 阶段 1：基础直连（优先级：P0）
**目标**：解决 DNS 污染，实现基本可访问

#### 1.1 硬编码 IP 表
在 `MissAvParsingHelper` 或新建 `MissAvDns.kt` 中实现：

```kotlin
object MissAvDns : Dns {
    // missav.ws 及其 CDN 的已知可用 IP
    private val BUILT_IN_IPS = listOf(
        "104.20.18.168", "104.20.19.168",  // Cloudflare Anycast
        "172.64.229.154", "162.159.0.1"    // CF 备用段
    )
    
    // 备用域名列表（如主域名被封锁）
    private val BACKUP_DOMAINS = listOf(
        "missav.ws", "missav.ai", "missav.wa", "missav.one"
    )
    
    override fun lookup(hostname: String): List<InetAddress> {
        return when {
            hostname in BUILT_IN_DOMAINS -> {
                BUILT_IN_IPS.map { InetAddress.getByName(it) }
            }
            hostname == "fourhoi.com" -> {
                // 图片 CDN 的 IP
                listOf(InetAddress.getByName("104.18.32.163"))
            }
            else -> Dns.SYSTEM.lookup(hostname)
        }
    }
}
```

#### 1.2 修改 DownloaderImpl
在 `DownloaderImpl.init()` 中集成自定义 DNS：

```kotlin
if (useDnsOverHttpsFallback || enableBuiltInHosts) {
    clientBuilder.dns(MissAvDns())
}
```

#### 1.3 UA 升级
将 `browserHeaders()` 中的 Firefox UA 改为 Chrome Mobile：

```kotlin
headers.put("User-Agent", Collections.singletonList(
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36"
))
```

---

### 阶段 2：增强稳定性（优先级：P1）
**目标**：IP 失效自动切换，多域名容灾

#### 2.1 IP 轮询 + 失效标记
```kotlin
class MissAvIpProvider {
    private val ips = listOf("104.20.18.168", "104.20.19.168", ...)
    private val unavailableIps = mutableMapOf<String, Long>() // IP -> 失效时间戳
    private var currentIndex = 0
    
    @Synchronized
    fun nextIp(): String {
        val now = System.currentTimeMillis()
        // 清理过期的失效 IP（5分钟过期）
        unavailableIps.entries.removeIf { now - it.value > 5 * 60 * 1000 }
        
        // 找一个可用的 IP
        repeat(ips.size) {
            val ip = ips[currentIndex]
            currentIndex = (currentIndex + 1) % ips.size
            if (!unavailableIps.containsKey(ip)) return ip
        }
        // 所有 IP 都失效，返回第一个（触发重试）
        return ips[0]
    }
    
    fun markUnavailable(ip: String) {
        unavailableIps[ip] = System.currentTimeMillis()
    }
}
```

#### 2.2 多域名自动切换
```kotlin
object MissAvDomainManager {
    private val domains = listOf("missav.ws", "missav.ai", "missav.wa")
    private var currentIndex = 0
    private val failedDomains = mutableSetOf<String>()
    
    @Synchronized
    fun currentDomain(): String {
        repeat(domains.size) {
            val domain = domains[currentIndex]
            currentIndex = (currentIndex + 1) % domains.size
            if (domain !in failedDomains) return domain
        }
        failedDomains.clear() // 全部失败，重置
        return domains[0]
    }
    
    fun markFailed(domain: String) {
        failedDomains.add(domain)
    }
}
```

---

### 阶段 3：SNI 绕过（优先级：P2）
**目标**：应对 SNI 深度检测

#### 3.1 SNI 替换模式
参考 Pix-EzViewer 的 `ReplaceSniSocketFactory`：

```kotlin
class MissAvSniSocketFactory(private val sniHost: String) : SSLSocketFactory() {
    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        val ip = socket.inetAddress.hostAddress
        return (delegate.createSocket(socket, ip, port, autoClose) as SSLSocket).apply {
            sslParameters = sslParameters.apply {
                serverNames = listOf(SNIHostName(sniHost))
            }
        }
    }
}
```

**SNI 候选列表**（需实测）：
- `missav.me`（非封锁域名，但证书可能不匹配）
- `fourhoi.com`（图片 CDN，可能共享证书）
- `cdn.cloudflare.com`（Cloudflare 边缘）

#### 3.2 空 SNI 模式（兼容性差，需配合 TrustAll）
```kotlin
class MissAvEmptySniSocketFactory : SSLSocketFactory() {
    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        val ip = socket.inetAddress.hostAddress
        return (delegate.createSocket(socket, null, port, autoClose) as SSLSocket).apply {
            sslParameters = sslParameters.apply {
        serverNames = emptyList()
            }
        }
    }
}
```

---

### 阶段 4：Cloudflare 挑战处理（优先级：P3）
**目标**：自动处理 Cloudflare 403 challenge

#### 4.1 CloudflareInterceptor
```kotlin
class MissAvCloudflareInterceptor(private val context: Context) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        
        if (response.code == 403 && 
            response.header("cf-mitigated") == "challenge") {
            response.close()
            
            val latch = CountDownLatch(1)
            MissAvCloudflareActivity.onFinished = { latch.countDown() }
            
            val intent = Intent(context, MissAvCloudflareActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("url", chain.request().url.toString())
            }
            context.startActivity(intent)
            
            latch.await(30, TimeUnit.SECONDS) // 30秒超时
            return chain.proceed(chain.request())
        }
        return response
    }
}
```

#### 4.2 CloudflareActivity（简化版）
```kotlin
class MissAvCloudflareActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript("document.head.innerHTML") { html ->
                    if (!html.contains("challenge-form") && 
                        html.contains("cf_clearance")) {
                        val cookies = CookieManager.getInstance().getCookie(url)
                        // 保存 Cookie 到 SharedPreferences
                        onFinished?.invoke()
                        finish()
                    }
                }
            }
        }
        
        webView.loadUrl(intent.getStringExtra("url"))
    }
    
    companion object {
        var onFinished: (() -> Unit)? = null
    }
}
```

---

## 代码约束与规范

### 1. 最小侵入原则
- 尽量在 `MissAvParsingHelper` 和 `DownloaderImpl` 中实现
- 避免大规模修改 UI 层
- 新代码放在 `logic/network/` 包下（参考 Han1meViewer 结构）

### 2. 兼容性
- **minSdk 26**（Android 8.0）
- **targetSdk 35**
- 使用 OkHttp 5.4.0 已有能力，避免引入新依赖

### 3. 配置持久化
使用 `SharedPreferences` 保存：
- `built_in_hosts_enabled`：是否启用内置 IP
- `custom_ips`：用户自定义 IP（逗号分隔）
- `doh_url`：DoH 服务地址
- `sni_mode`：SNI 模式（plain/replace/empty）
- `current_domain_index`：当前域名索引

### 4. 日志与调试
- 所有 DNS 解析结果打日志（Tag: `MissAvDns`）
- 记录 IP 失效事件
- 提供"测试连接"功能（Preferences 中）

---

## 具体任务清单

### 立即执行（Phase 1）
- [ ] 创建 `MissAvDns.kt`，实现硬编码 IP + fallback
- [ ] 修改 `DownloaderImpl.init()`，集成 `MissAvDns`
- [ ] 修改 `MissAvParsingHelper.browserHeaders()`，升级 UA
- [ ] 添加 `use_built_in_hosts` 开关到 `NewPipeSettings`

### 短期执行（Phase 2）
- [ ] 实现 `MissAvIpProvider`（IP 轮询 + 失效标记）
- [ ] 实现 `MissAvDomainManager`（多域名容灾）
- [ ] 修改 `MissAvParsingHelper.localizeUrl()`，支持动态域名切换
- [ ] 添加用户自定义 IP 输入界面

### 中期执行（Phase 3）
- [ ] 实现 `MissAvSniSocketFactory`（SNI 替换）
- [ ] 实现 `MissAvEmptySniSocketFactory`（空 SNI）
- [ ] 在 `DownloaderImpl` 中集成 SNI 模式切换
- [ ] 添加 SNI 自动探测逻辑（读取源站证书 SAN）

### 长期执行（Phase 4）
- [ ] 实现 `MissAvCloudflareInterceptor`
- [ ] 实现 `MissAvCloudflareActivity`
- [ ] 集成到主 OkHttpClient

---

## 验证标准

### 功能验证
1. **DNS 解析**：在污染网络环境下，`missav.ws` 解析到内置 IP 而非污染 IP
2. **IP 轮询**：连续请求使用不同 IP（可通过日志验证）
3. **域名切换**：主域名不可用时自动切换到备用域名
4. **SNI 绕过**：Wireshark 抓包确认 SNI 字段为替换值或为空

### 兼容性验证
1. **Android 8.0 (API 26)**：基本功能正常
2. **Android 14 (API 35)**：targetSdk 兼容
3. **有代理环境**：直连功能可关闭，不影响代理使用
4. **无代理环境**：核心功能（搜索、播放）正常

---

## 参考资料文件

| 文件 | 用途 |
|------|------|
| `/workspace/eh/ehviewer_direct_connect_analysis.md` | E-Hentai 客户端直连方案 |
| `/workspace/han/hanime_direct_connect_analysis.md` | Han1meViewer 网络层架构 |
| `/workspace/pix/pixiv_direct_connect_analysis.md` | Pixiv 客户端 SNI 绕过方案 |
| `/workspace/pron/MISSPipe/PipePipeClient/app/src/main/java/org/schabi/newpipe/DownloaderImpl.java` | 当前网络层实现 |
| `/workspace/pron/MISSPipe/PipePipeExtractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/missav/MissAvParsingHelper.java` | MissAV 解析逻辑 |

---

## 输出要求

请按以下格式输出实现代码：

### 1. 新建文件
```kotlin
// File: PipePipeClient/app/src/main/java/org/schabi/newpipe/network/MissAvDns.kt
// Description: 自定义 DNS 解析器
// ...
```

### 2. 修改文件
```kotlin
// File: PipePipeClient/app/src/main/java/org/schabi/newpipe/DownloaderImpl.java
// Change: 在 init() 方法中添加 MissAvDns 集成
// Original: ...
// Modified: ...
```

### 3. 配置变更
```xml
<!-- File: PipePipeClient/app/src/main/java/org/schabi/newpipe/settings/NewPipeSettings.java -->
<!-- 新增 Keys: -->
<key name="built_in_hosts_enabled" type="boolean">true</key>
```

---

## 注意事项

1. **不要删除任何现有代码**，所有修改都是增量添加
2. **保持 GPL-3.0 协议**，所有新代码同样 GPL-3.0
3. **硬编码 IP 必须加注释**，说明来源和更新时间
4. **提供降级方案**：直连失败时自动 fallback 到系统 DNS
5. **不要硬编码 Token/密钥**：`MissAvParsingHelper.PUBLIC_TOKEN` 已是公开信息，但仍需注释说明

---

## 开始执行

请从 **阶段 1** 开始，先实现 `MissAvDns.kt` 和 `DownloaderImpl` 的修改。完成后我会进行代码审查，再进入下一阶段。
