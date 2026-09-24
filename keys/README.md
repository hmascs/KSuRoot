# 签名密钥：AOSP testkey（**公开密钥，不是私密凭据**）

## 为什么要用这把

设备上**已安装**的 `com.ting.root`，以及 `ksusync/` 里从头到尾所有版本
（3.0.1 / 3.0.2 / 3.0.3beta / 3.0.3beta2 及其 `_sign` 版）**全部**用同一把密钥签名：

```
certificate DN: EMAILADDRESS=android@android.com, CN=Android, OU=Android, O=Android,
                L=Mountain View, ST=California, C=US
SHA-256: a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc
SHA-1  : 61ed377e85d386a8dfee6b864bd85b0bfaa5af81
```

这把就是 **AOSP 官方 `build/target/product/security/testkey`**（逐字节核对过证书 DER 的 SHA-256）。
它随 AOSP 源码公开分发，**任何人都有**，所以它不是"私钥泄漏"，也不提供任何身份保证 ——
它只保证"能和现有安装原地升级"。

**安全含义（必须知道）**：用 testkey 签名的 APK，任何拿到这把公开密钥的人都能冒充出
同样签名的包。所以这份构建**只适合自用/内测**，不要当成有身份保障的正式发布件。

## 由来

`ksu-toolchain/package_beta.sh` 早先用的是自造密钥 `ksuroot-beta.jks`
（DN `CN=KSuRoot Beta`，SHA-1 `d283d904…`）—— 那把与设备上的签名**不一致**，
所以用它签出来的包**无法原地升级**，必须卸载重装。已改用 testkey。

## 文件

| 文件 | 说明 |
|---|---|
| `testkey.pk8` | PKCS#8 DER 私钥（1217 字节，头 `30 82 04 bd 02 01 00`） |
| `testkey.x509.pem` | 对应证书（公开） |

已用 openssl 校验过两者是**同一对**（公钥 SHA-256 均为
`ef57b690165cb561b5026922c00d2d6574e8b184fa7d161e076f06e06e6d35db`）。

来源：`https://raw.githubusercontent.com/aosp-mirror/platform_build/master/target/product/security/`
