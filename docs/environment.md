# 环境

> 操作手册见 [`../AGENTS.md`](../AGENTS.md)。

本机已安装的工具链（路径直接照抄，无需再配置）：

| 项 | 值 |
| --- | --- |
| DevEco Studio | `/Applications/DevEco-Studio.app`（版本 26.0.0） |
| SDK | `/Applications/DevEco-Studio.app/Contents/sdk`（**API 26**，HarmonyOS 26.0.0） |
| hvigor | `/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw` |
| hdc | `/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc` |
| node / ohpm | `/Applications/DevEco-Studio.app/Contents/tools/{node,ohpm}/bin` |
| 真机 | HUAWEI Pura 70 Pro+，HarmonyOS **6.1.1 / API 24** |

> ⚠️ SDK 是 API 26，但设备是 API 24，所以 `build-profile.json5` 里
> `compatibleSdkVersion` / `targetSdkVersion` 必须是 `"6.1.1(24)"`（不是 `26.0.0`，也不是 `5.0.0(12)`）。
