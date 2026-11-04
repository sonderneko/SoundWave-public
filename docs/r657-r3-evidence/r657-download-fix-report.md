# r657 F7 单流下载 Content-Range 修复

## 范围和结果

工作树：`/root/soundwave-r657-r3`。先读取 `/tmp/r657-reliability-findings.md` F7，再亲自检查 `DownloadHelper.kt` 的 probe、single、multi、finalize 和现有 `DownloadResumePolicy`。

确认原缺陷：单流只用 `resumeFrom > 0 && code == 206` 决定 append，没有读取 Content-Range；原 finalize 继续使用 probe total，即使 GET 200 的实体长度已变化。

修改文件：
- `app/src/main/java/xyz/cncnlp/soundwave/DownloadHelper.kt`
- 新增 `app/src/main/java/xyz/cncnlp/soundwave/download/ContentRangePolicy.kt`
- 新增 `app/src/test/java/xyz/cncnlp/soundwave/download/ContentRangePolicyTest.kt`
- 新增 `app/src/test/java/xyz/cncnlp/soundwave/download/DownloadSingleContentRangeTest.kt`

`DownloadResumePolicy.kt` 无需改动，已逐字与 HEAD 比较确认未变；downloadMulti 及其后所有源码也逐字确认与 HEAD 一致。未修改其它工作流文件。

## 最小实现

- 严格解析单个 `bytes start-end/total`：必须十进制非负数、可表示为 Long、start <= end < total，拒绝未知 total、unsatisfied range、多范围/尾随垃圾和溢出。
- 206 在打开输出文件前验证 start == 请求 offset、end == total-1、total == 已知实体总长、声明 Content-Length（若有）== 范围长度。offset 0 的 206 同样必须覆盖完整实体。
- 续传仍要求现有 ETag/Last-Modified 一致；probe 不知道 total 而 stored fingerprint 知道时，把 stored total 用于本次 206 校验。
- 读取时累计实际 body bytes，超长在写入该 buffer 前失败，EOF 后严格校验短读；因此 chunked/未知 Content-Length 也不能绕过。
- 200 始终 truncate，不 append；采用实际 GET 长度，未知长度则采用成功读完的实际字节数，返回给 finalize，避免用旧 probe total 误判。覆盖时更新现有格式的 `.resume` 元数据为实际响应 total/validator，缺 validator 删元数据。
- 错误范围抛 IOException，downloadSingle 不成功返回，无法进入后续 done/SAF 提交。
- 保留原有元数据格式、validator 策略和多段逻辑；没有扩张 F8 的 origin/平台身份/强 ETag 设计、call registry 或 writer 生命周期。

## 测试与证据分级

先写两份回归测试，再执行 ad-hoc RED 源码断言，最后最小实现。

### 实际运行：仅 ad-hoc，不是 Kotlin/JUnit

RED：Python 对旧源码的四项接线断言均 FAIL，exit 1：
- strict range policy exists
- single checks response range
- single checks actual body bytes
- single returns fresh total

GREEN：最终 Python 源码断言 14/14 PASS，exit 0，覆盖 parser/接线存在、输出前校验、超长写入前拒绝、200 覆盖、实际 metadata/total、stored total 保留、多段不变、resume policy 不变、测试文件存在。

`git diff --check`：exit 0。

这些结果只是 **ad-hoc 源码断言**，不是执行 Kotlin 实现的行为证明，不能声称测试全绿或 Android 可编译。

### 已新增但未执行：9 个 JUnit 测试方法

`ContentRangePolicyTest`（3）：严格 parser、续传 start/total/尾区间/声明正文长度/实际短长读、offset 0 完整实体要求。

`DownloadSingleContentRangeTest`（6）：Robolectric + MockWebServer，反射调用生产 private downloadSingle 避开 MediaExtractor/SAF finalize；测试正常 append 和 Range/If-Range 请求、同 ETag 错 start/total/缺头、chunked 短读/超长、offset 0 错206拒绝/完整206接受、200覆盖且刷新实际 total/metadata、未知长度200不用旧 probe total。测试中使用实际临时文件并清理 `.part`/`.resume`。

## 限制 / 待验证

遵守约束：未安装 SDK、未运行 Gradle/kotlinc、未 commit/push/升版。本机不能 Android 编译，因此新增 JUnit/MockWebServer 测试没有执行；需在父任务允许的现有 CI 中验证编译和行为。没有 APK 或真机证据。JUnit RED 也未执行，实际 RED 仅是明示的 ad-hoc 源码缺接线断言。

已知 F8（弱/缺来源身份的 fingerprint、activeCalls 注册删除竞态）仍属独立问题，未声称本次解决。
