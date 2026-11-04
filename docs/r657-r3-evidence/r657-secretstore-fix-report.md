# r657 SecretStore caller-provided IV 修复

## 结果

已在 `/root/soundwave-r657-r3` 最小修复确定根因：AndroidKeyStore AES/GCM 密钥要求 `randomized=true`，旧加密代码显式传入 `GCMParameterSpec(128, callerIv)`，违反 provider 策略，异常被吞后重复三次返回 null；账号及 WebDAV 共用 SecretStore，因此同时无法保存密码。

生产代码现改为：

```kotlin
val cipher = Cipher.getInstance("AES/GCM/NoPadding")
cipher.init(Cipher.ENCRYPT_MODE, keyForEncrypt())
val iv = cipher.iv
```

由 AndroidKeyStore provider 生成 IV。移除无用 SecureRandom import。保持 alias `cloud_cred_password`、`v2:` + Base64(12-byte IV || ciphertext+tag) 序列化、AES-256/GCM、randomized=true、既有重试策略；不删钥、不覆盖既有钥、不回存明文。已用 HEAD 对照断言确认 decrypt/classification/key generation/retry 部分逐字未改，旧 v2 解密路径不变。

## 修改/新增文件

- 修改：`app/src/main/java/xyz/cncnlp/soundwave/SecretStore.kt`。
- 新增：`app/src/androidTest/java/xyz/cncnlp/soundwave/SecretStoreAndroidKeyStoreTest.kt`。
- 新增辅助产物：`/tmp/r657-secretstore-contract-check.py`、`/tmp/r657-secretstore-red.log`、`/tmp/r657-secretstore-green.log`、本报告。

未改 CloudSyncController、MainActivity、AvatarStore、WebDAV 实现或 Gradle 配置。工作树存在其他代理改动，未触碰/回滚。没有 commit/push/升版。

## 测试先行与已执行验证

先落地 androidTest 和源码契约断言，后修改生产文件。

执行 `python3 /tmp/r657-secretstore-contract-check.py`：
- 修复前：3 个源码契约检查中 1 个 FAIL，预期失败项 `test_provider_generated_encryption_iv`，精确指出仍向 randomized=true 密钥传 caller IV（exit 1）。日志 `/tmp/r657-secretstore-red.log`。
- 修复后：3 个源码契约检查全部 OK（exit 0）。日志 `/tmp/r657-secretstore-green.log`；最终清理 import 后再次全过。
- 额外 HEAD 比较断言：`PASS: decrypt/classification/key generation/retry code unchanged from HEAD`。
- 限定文件 `git diff --check` 通过。

以上仅源码约束验证，不是 Android 加密运行结果，不声称完成 Android runtime 的 RED/GREEN。

## 已添加真实 AndroidKeyStore instrumentation 测试（全部未运行）

5 个 @Test，使用 AndroidJUnit4 和真实 `AndroidKeyStore`（无 mock）：
1. 账号/WebDAV 风格 Unicode/特殊字符密码 roundtrip、classify、原 v2 布局长度。
2. 同一密码两次加密 IV 不同，且两份密文都能解密。
3. 从既有生产 alias 取钥，用独立 Cipher 构造原 v2 布局密文；后续 encrypt 前后都可解密，防止换钥或格式漂移。
4. KeyInfo 确认 randomizedEncryptionRequired=true，并断言显式 caller IV 加密抛 InvalidAlgorithmParameterException，直接覆盖原故障合约。
5. 空密码语义与 GCM 篡改密文判 Corrupted。

兼容测试使用独立 provider 加密生成的原 v2 wire-format 样本，不是用户设备旧密文，也不伪称已有设备迁移实测。AndroidKeyStore 密钥设备绑定，测试不导出或替换密钥，不删除生产 alias，不修改账号偏好。

## 验证边界/待验收

按委派要求未安装/调用 Android SDK、未执行 Gradle、未编译 APK、未执行 instrumentation/真机或重启测试。待具备 SDK 与模拟器/设备的环境运行（本次未运行）：

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=xyz.cncnlp.soundwave.SecretStoreAndroidKeyStoreTest
```

还需实际验证账号登录保存后强退/冷启动恢复，以及 WebDAV 登录同步保存后重启恢复；本次仅修复共享加密根因，不扩张为登录流程/错误提示/账号绑定等其他文件已修。

执行中少量工具 pre_tool_call 超时；辅助 Python 脚本首次写入未成功，随后重试落盘并真实执行。无编译或设备结果被替代/伪造。
