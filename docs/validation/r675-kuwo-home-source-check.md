# 酷我首页：新碟接口确实只有一张，数字专辑尚无已验证来源

这轮没有改生产取数或界面代码，也不能宣称“数字专辑／新碟数量问题已修好”。已确认：首页现用酷我新碟接口本身只返回一张，不是客户端把多张截成一张；数字专辑则根本没有接入酷我来源。按“先有源端证据，再做最小修复”的约束，本轮只新增实况证据和针对性回归。

## 新碟为什么只有一张

`HomeFeedController.kt:250、363` 调用发现接口时传入 `limit=20`；`FeedDirectFetch.kt:101–104` 对酷我请求 [disc/shelves?pn=1&rn=20](https://wapi.kuwo.cn/openapi/v1/pc/disc/shelves?pn=1&rn=20)，另一路搜索结果仅标为新歌，不冒充新碟。

按客户端相同 User-Agent、Referer 和 Accept，用匿名 GET 取得以下结果，原始响应与元信息保存在 [证据目录](kw-home-20261023/)。

| 请求 | HTTP / 业务码 | data.size | albumList 实际条数 |
| --- | --- | --- | --- |
| pn=1，rn=20：首页实际参数 | 200 / 200 | 1 | 1 |
| pn=1，rn=12 | 200 / 200 | 1 | 1 |
| pn=1，rn=18：初始探测 | 200 / 200 | 1 | 1 |
| pn=1，rn=30：增大请求量 | 200 / 200 | 1 | 1 |
| pn=2，rn=12：第二页 | 200 / 200 | 1 | 0 |
| pn=0，rn=12：排查页码起点 | 200 / 200 | 1 | 1 |

唯一专辑均为罗中旭《未完待旭 罗中旭经典全纪录1995-2017》，`albumid=4965208`，`pay=0`。第二页仍报告 `size=1`，但列表为空，不能用这个字段制造额外条目。仓库既有 `round6/kw-shelves.json` 夹具也只有同一张。

`parseKwNewAlbums`（`FeedDirectFetch.kt:623–632`）完整保留这张专辑；归一化按类别分别限额，不会被新歌挤掉。`HomePage.kt:827–845` 按实际新碟列表长度生成轮播，没有强制一张的上限；缓存序列化也不对 discover 截断。现场请求的“1条”与解析输出一致，未获得源端有更多条目而客户端漏接的证据。

**此结论只针对当前选用的这个接口。它不能证明酷我整个平台只有一张新碟，也不能证明此接口代表完整或最新目录。**

## 数字专辑为什么没有

酷我发现请求目前只组装 `new_album` 和 `new_song`，没有数字专辑请求或 `kind=digital` 映射。首页只在收到 digital 卡片时显示数字专辑区块（`HomePage.kt:821–825`）；数字轮播的 `take(12)` 只是展示上限，不是“完全没有”的原因。

检索找到酷我官方[数字专辑专题](https://mobile.kuwo.cn/mpage/fspage/zhuanqu/index.html?mainid=48)。页面确有标题和加载脚本，但标题不能证明存在可用专辑数据。依其内联脚本访问 `getdata.jsp?mainid=48`，无跟随重定向时为 **301 → https://www.kuwo.cn**；跟随请求最终得到 **HTTP 500、HTML而非目录JSON**。页面HTML、官方脚本、301原始响应、最终500原始响应及状态均已留存。

因此，目前只能确认“客户端缺来源”，不能确认一个可匿名正常取到、带真实专辑ID且适合接入的酷我数字专辑目录。没有把 pay=0 新碟、搜索歌曲或其他平台内容改标为数字专辑，也没有添加占位或随机数量。

## 本轮改动和实际测试

仅新增 `KwHomeCatalogEvidenceTest.kt` 的6项测试、4份现场JSON夹具、接口核查脚本和证据／报告。生产源码相对 `fc0db46b` **零差异**。

测试覆盖：实际20条请求一张不漏；增大请求量仍为同一张；空第二页不会扩张数据；明确标注的合成测试数据验证解析及归一化没有“一张上限”；重复／无身份记录过滤；错误与缺目录响应不能变成占位专辑。合成数据仅在单元测试中使用，不进入应用或网络回退。

| 轮次 | 真实结果 |
| --- | --- |
| 首轮：7个测试类、47项 | 46通过、1失败、0 errors、0跳过 |
| 修正测试预期后的同范围复测 | **47通过、0失败、0 errors、0跳过** |

首轮失败原因：新增测试直接把源端含不间断空格的标题与解析后的标题比较，而现有 `cleanSpace` 会去掉该空格。只修正测试预期，没有修改标题清理或生产解析；复测首屏夹具改用后续补抓的实际 `rn=20` 响应。首轮失败源码、XML和日志全部保留，没有把它说成生产故障已修复。最终结果由XML汇总于 [test-summary.json](kw-home-20261023/test-summary.json)。

实际命令：

```sh
ANDROID_HOME=/home/ubuntu/Android/Sdk bash gradlew --no-daemon --max-workers=2 \
  -Dorg.gradle.jvmargs='-Xmx3g -Dfile.encoding=UTF-8' :app:testDebugUnitTest \
  --tests 'xyz.cncnlp.soundwave.source.direct.KwHomeCatalogEvidenceTest' \
  --tests 'xyz.cncnlp.soundwave.source.direct.FeedDirectFetchTest' \
  --tests 'xyz.cncnlp.soundwave.source.direct.Round6CatalogTest' \
  --tests 'xyz.cncnlp.soundwave.HomeFeed*Test'
```

本轮包含首页缓存策略、平台新鲜度、搜索缓存键、快照IO、发现解析和既有目录回归。Gradle提示SDK XML工具版本不一致；最终构建与测试成功。生产编译任务命中已有缓存，并非从零重编译。没有跑全套测试、Lint、真机或完整平台网络联测，不能外推为全量通过。两轮同范围测试不累计成94项。

## 仍未确认，不能当作修复

- 尚未找到并验证更完整的新碟官方目录或可用数字专辑目录；旧专题失败不等于所有酷我入口都不可用。
- `ApiClient.kt:491–499` 只要直连聚合非空就返回成功，某个 section 缺失不会单独回落服务器，这是已确认策略，但本轮没有证据证明服务器可补齐目录，故未擅自重构回退。
- 未登录用户手机、读取其缓存或执行APK运行时抓包；网络实况来自当前工作环境，不等同于用户网络。没有登录VPS或改服务器，不能断言其目录状态。

后续应先验证可用官方目录、真实专辑ID、详情可打开及分类身份，再补对应来源；目前不应通过增加分页次数或硬编码数量掩盖源端缺口。

## 保持的边界

未生成APK、打包、安装或推送；未改版本、母版ZIP或既有计划文件；未修改其他分支、`/workspace/soundwave-675-ui-lint`、`/workspace/soundwave-shadow-675`，也未触碰已完成的详情页与播放器间距。本轮只在 `/workspace/soundwave-675-improve` 累积证据和测试。
